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

    @Nested
    inner class DetectCascadeEventsTests {

        @Test
        fun `task completion triggers all_tasks_complete when all tasks done`() = runBlocking {
            // Setup: task is completed, feature has total=3, completed=3
            val completedTask = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 3,
                pending = 0,
                inProgress = 0,
                testing = 0,
                completed = 2,
                cancelled = 1,
                blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("in-development", "feature", feature.tags, feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "testing",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Ready to progress"
            )

            // Execute
            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // Assert: returns CascadeEvent with event="all_tasks_complete"
            assertEquals(1, events.size)
            val event = events.first()
            assertEquals("all_tasks_complete", event.event)
            assertEquals(ContainerType.FEATURE, event.targetType)
            assertEquals(featureId, event.targetId)
            assertEquals("in-development", event.currentStatus)
            assertEquals("testing", event.suggestedStatus)
            assertEquals("default_flow", event.flow)
            assertTrue(event.automatic)
        }

        @Test
        fun `first task in-progress triggers first_task_started`() = runBlocking {
            // Setup: task at in-progress, feature at first flow status ("planning"), only 1 in-progress task
            val inProgressTask = createTestTask(status = TaskStatus.IN_PROGRESS)
            val feature = createTestFeature(status = FeatureStatus.PLANNING)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)

            // Mock countTasksByStatus - only 1 in-progress task
            coEvery { mockTaskRepository.findByFeatureId(featureId) } returns listOf(
                inProgressTask,
                createTestTask(id = UUID.randomUUID(), status = TaskStatus.PENDING),
                createTestTask(id = UUID.randomUUID(), status = TaskStatus.PENDING)
            )

            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "planning") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 0,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("planning", "feature", feature.tags, feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-development",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 0,
                matchedTags = emptyList(),
                reason = "First task started"
            )

            // Execute
            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // Assert: returns CascadeEvent with event="first_task_started"
            assertEquals(1, events.size)
            val event = events.first()
            assertEquals("first_task_started", event.event)
            assertEquals(ContainerType.FEATURE, event.targetType)
            assertEquals(featureId, event.targetId)
            assertEquals("planning", event.currentStatus)
            assertEquals("in-development", event.suggestedStatus)
        }

        @Test
        fun `no cascade when task has no feature`() = runBlocking {
            // Setup: task has featureId = null
            val orphanTask = createTestTask(featureId = null)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(orphanTask)

            // Execute
            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // Assert: returns empty list
            assertTrue(events.isEmpty())
        }

        @Test
        fun `no cascade when not all tasks complete`() = runBlocking {
            // Setup: task completed but feature has total=3, completed=2
            val completedTask = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 3,
                completed = 2,  // Not all complete
                pending = 1,
                inProgress = 0,
                testing = 0,
                cancelled = 0,
                blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )

            // Execute
            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // Assert: returns empty list
            assertTrue(events.isEmpty())
        }

        @Test
        fun `feature completion triggers all_features_complete when all features done`() = runBlocking {
            // Setup: feature at terminal status ("completed"), project has total=2, completed=2
            val feature = createTestFeature(status = FeatureStatus.COMPLETED)
            val project = createTestProject(status = ProjectStatus.IN_DEVELOPMENT)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2,
                completed = 2
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "completed") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 3,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("in-development", "project", project.tags, project.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "completed",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "All features complete"
            )

            // Execute
            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            // Assert: returns CascadeEvent for project
            assertEquals(1, events.size)
            val event = events.first()
            assertEquals("all_features_complete", event.event)
            assertEquals(ContainerType.PROJECT, event.targetType)
            assertEquals(projectId, event.targetId)
            assertEquals("in-development", event.currentStatus)
            assertEquals("completed", event.suggestedStatus)
        }

        @Test
        fun `no project cascade when features still active`() = runBlocking {
            // Setup: feature completed but project has total=3, completed=1
            val feature = createTestFeature(status = FeatureStatus.COMPLETED)
            val project = createTestProject(status = ProjectStatus.IN_DEVELOPMENT)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 3,
                completed = 1  // Not all complete
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "completed") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 3,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )

            // Execute
            val events = cascadeService.detectCascadeEvents(featureId, ContainerType.FEATURE)

            // Assert: returns empty list
            assertTrue(events.isEmpty())
        }

        @Test
        fun `projects have no cascades upward`() = runBlocking {
            // Execute
            val events = cascadeService.detectCascadeEvents(projectId, ContainerType.PROJECT)

            // Assert: projects are top-level, no cascades
            assertTrue(events.isEmpty())
        }
    }

    @Nested
    inner class FindNewlyUnblockedTasksTests {

        @Test
        fun `returns unblocked tasks when all blockers resolved`() = runBlocking {
            // Setup: task A completed, has BLOCKS dependency to task B, task B's only blocker is task A
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskA = createTestTask(id = taskAId, status = TaskStatus.COMPLETED)
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)

            val blocksDependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskAId,
                toTaskId = taskBId,
                type = DependencyType.BLOCKS,
                createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(blocksDependency)
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(blocksDependency)

            // Execute
            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            // Assert: returns UnblockedTask for task B
            assertEquals(1, unblocked.size)
            assertEquals(taskBId, unblocked.first().taskId)
            assertEquals("Test Task", unblocked.first().title)
        }

        @Test
        fun `does not return task still blocked by other tasks`() = runBlocking {
            // Setup: task A completed, task B blocked by both A and C, C still in-progress
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskCId = UUID.randomUUID()
            val taskB = createTestTask(id = taskBId, status = TaskStatus.PENDING)
            val taskC = createTestTask(id = taskCId, status = TaskStatus.IN_PROGRESS)

            val depAtoB = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskAId,
                toTaskId = taskBId,
                type = DependencyType.BLOCKS,
                createdAt = Instant.now()
            )
            val depCtoB = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskCId,
                toTaskId = taskBId,
                type = DependencyType.BLOCKS,
                createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(depAtoB)
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(depAtoB, depCtoB)
            coEvery { mockTaskRepository.getById(taskCId) } returns Result.Success(taskC)

            // Execute
            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            // Assert: returns empty list (task B still blocked by task C)
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `does not return already-completed downstream tasks`() = runBlocking {
            // Setup: downstream task already completed
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()
            val taskB = createTestTask(id = taskBId, status = TaskStatus.COMPLETED)

            val blocksDependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskAId,
                toTaskId = taskBId,
                type = DependencyType.BLOCKS,
                createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(blocksDependency)
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)

            // Execute
            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            // Assert: returns empty list (downstream task already done)
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `ignores RELATES_TO dependencies`() = runBlocking {
            // Setup: only RELATES_TO dependency from completed task
            val taskAId = UUID.randomUUID()
            val taskBId = UUID.randomUUID()

            val relatesDependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskAId,
                toTaskId = taskBId,
                type = DependencyType.RELATES_TO,
                createdAt = Instant.now()
            )

            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(relatesDependency)

            // Execute
            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            // Assert: returns empty list (RELATES_TO doesn't block)
            assertTrue(unblocked.isEmpty())
        }

        @Test
        fun `handles task with no outgoing dependencies`() = runBlocking {
            // Setup: task has no outgoing dependencies
            val taskAId = UUID.randomUUID()
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns emptyList()

            // Execute
            val unblocked = cascadeService.findNewlyUnblockedTasks(taskAId)

            // Assert: returns empty list
            assertTrue(unblocked.isEmpty())
        }
    }

    @Nested
    inner class ApplyCascadesTests {

        @Test
        fun `applies cascade and returns result`() = runBlocking {
            // Setup: mock detectCascadeEvents to return one event, mock validation as Valid, mock status change
            val task = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            // Mock detection
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1,
                completed = 1,
                pending = 0,
                inProgress = 0,
                testing = 0,
                cancelled = 0,
                blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("in-development", "feature", feature.tags, feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "testing",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "All tasks complete"
            )

            // Mock validation
            coEvery {
                mockStatusValidator.validateTransition(
                    currentStatus = "in-development",
                    newStatus = "testing",
                    containerType = "feature",
                    containerId = featureId,
                    context = any(),
                    tags = feature.tags
                )
            } returns StatusValidator.ValidationResult.Valid

            // Mock status update
            coEvery { mockFeatureRepository.update(any()) } answers {
                val updatedFeature = firstArg<Feature>()
                Result.Success(updatedFeature)
            }

            // Execute
            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)

            // Assert: returns AppliedCascade with applied=true
            assertEquals(1, results.size)
            val result = results.first()
            assertEquals("all_tasks_complete", result.event)
            assertTrue(result.applied)
            assertEquals("in-development", result.previousStatus)
            assertEquals("testing", result.newStatus)
            assertNull(result.error)
        }

        @Test
        fun `stops at max depth`() = runBlocking {
            // Call applyCascades with depth >= maxDepth
            val results = cascadeService.applyCascades(taskId, "task", depth = 3, maxDepth = 3)

            // Assert: returns empty list
            assertTrue(results.isEmpty())
        }

        @Test
        fun `skips cascade when target already at suggested status`() = runBlocking {
            // Setup: feature already at the suggested status
            val task = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.TESTING)  // Already at target status

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1,
                completed = 1,
                pending = 0,
                inProgress = 0,
                testing = 0,
                cancelled = 0,
                blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "testing") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("testing", "feature", feature.tags, feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "testing",  // Same as current
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2,
                matchedTags = emptyList(),
                reason = "Already at target"
            )

            // Execute
            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)

            // Assert: returns empty list (cascade skipped)
            assertTrue(results.isEmpty())
        }

        @Test
        fun `handles validation failure gracefully`() = runBlocking {
            // Setup: mock validation as Invalid
            val task = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1,
                completed = 1,
                pending = 0,
                inProgress = 0,
                testing = 0,
                cancelled = 0,
                blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("in-development", "feature", feature.tags, feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "testing",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "All tasks complete"
            )

            // Mock validation failure
            coEvery {
                mockStatusValidator.validateTransition(
                    currentStatus = "in-development",
                    newStatus = "testing",
                    containerType = "feature",
                    containerId = featureId,
                    context = any(),
                    tags = feature.tags
                )
            } returns StatusValidator.ValidationResult.Invalid("Prerequisites not met")

            // Execute
            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)

            // Assert: returns AppliedCascade with applied=false, error set
            assertEquals(1, results.size)
            val result = results.first()
            assertFalse(result.applied)
            assertEquals("Transition blocked: Prerequisites not met", result.error)
        }

        @Test
        fun `handles entity not found gracefully`() = runBlocking {
            // Setup: task exists but feature doesn't
            val task = createTestTask(status = TaskStatus.COMPLETED)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Error(
                RepositoryError.NotFound(featureId, EntityType.FEATURE, "Not found")
            )

            // Execute
            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)

            // Assert: returns empty list (no cascades detected)
            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class LoadAutoCascadeConfigTests {

        @Test
        fun `returns defaults when no config file exists`() {
            // Execute (config file doesn't exist in test environment)
            val config = cascadeService.loadAutoCascadeConfig()

            // Assert: returns AutoCascadeConfig(enabled=true, maxDepth=3)
            assertTrue(config.enabled)
            assertEquals(3, config.maxDepth)
        }

        @Test
        fun `loads from bundled default config`() {
            // Execute (should fall back to bundled config)
            val config = cascadeService.loadAutoCascadeConfig()

            // Assert: returns valid config
            assertNotNull(config)
            assertTrue(config.enabled)
            assertEquals(3, config.maxDepth)
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `handles task with cancelled status as terminal`() = runBlocking {
            // Setup: task cancelled, feature has all tasks cancelled
            val cancelledTask = createTestTask(status = TaskStatus.CANCELLED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(cancelledTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 2,
                cancelled = 2,
                completed = 0,
                pending = 0,
                inProgress = 0,
                testing = 0,
                blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("in-development", "feature", feature.tags, feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "cancelled",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "All tasks cancelled"
            )

            // Execute
            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // Assert: cancelled tasks count as terminal for cascade detection
            assertEquals(1, events.size)
            assertEquals("all_tasks_complete", events.first().event)
        }

        @Test
        fun `handles feature with no tasks gracefully`() = runBlocking {
            // Setup: task completed, but feature has no tasks (edge case)
            val task = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 0,
                completed = 0,
                pending = 0,
                inProgress = 0,
                testing = 0,
                cancelled = 0,
                blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )

            // Execute
            val events = cascadeService.detectCascadeEvents(taskId, ContainerType.TASK)

            // Assert: no cascade when feature has no tasks
            assertTrue(events.isEmpty())
        }

        @Test
        fun `handles repository errors during cascade application`() = runBlocking {
            // Setup: detection succeeds but update fails
            val task = createTestTask(status = TaskStatus.COMPLETED)
            val feature = createTestFeature(status = FeatureStatus.IN_DEVELOPMENT)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1,
                completed = 1,
                pending = 0,
                inProgress = 0,
                testing = 0,
                cancelled = 0,
                blocked = 0
            )
            every { mockStatusProgressionService.getFlowPath("feature", feature.tags, "in-development") } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "cancelled", "on-hold")
            )
            coEvery {
                mockStatusProgressionService.getNextStatus("in-development", "feature", feature.tags, feature.id)
            } returns NextStatusRecommendation.Ready(
                recommendedStatus = "testing",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "All tasks complete"
            )
            coEvery {
                mockStatusValidator.validateTransition(any(), any(), any(), any(), any(), any())
            } returns StatusValidator.ValidationResult.Valid

            // Mock update failure
            coEvery { mockFeatureRepository.update(any()) } returns Result.Error(
                RepositoryError.DatabaseError("Update failed")
            )

            // Execute
            val results = cascadeService.applyCascades(taskId, "task", depth = 0, maxDepth = 3)

            // Assert: cascade marked as failed with error
            assertEquals(1, results.size)
            assertFalse(results.first().applied)
            assertNotNull(results.first().error)
            assertTrue(results.first().error!!.contains("Failed to update feature"))
        }
    }
}
