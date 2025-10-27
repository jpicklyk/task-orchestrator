package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.ProjectRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for WorkflowServiceImpl.
 *
 * Tests cover:
 * - Happy path: Cascade detection for all event types (first_task_started, all_tasks_complete, all_features_complete)
 * - Happy path: Flow determination based on tags
 * - Happy path: Config-driven behavior with custom flows
 * - Error path: Missing entities return empty events
 * - Error path: Invalid UUIDs handled gracefully
 * - Exception path: Repository failures handled
 * - Config-driven verification: No hardcoded enum checks
 */
class WorkflowServiceTest {

    private lateinit var workflowConfigLoader: WorkflowConfigLoader
    private lateinit var taskRepository: TaskRepository
    private lateinit var featureRepository: FeatureRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var statusValidator: StatusValidator
    private lateinit var workflowService: WorkflowServiceImpl

    private val testTaskId = UUID.randomUUID()
    private val testFeatureId = UUID.randomUUID()
    private val testProjectId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create mocks
        workflowConfigLoader = mockk<WorkflowConfigLoader>()
        taskRepository = mockk<TaskRepository>()
        featureRepository = mockk<FeatureRepository>()
        projectRepository = mockk<ProjectRepository>()
        statusValidator = mockk<StatusValidator>()

        // Create service instance
        workflowService = WorkflowServiceImpl(
            workflowConfigLoader = workflowConfigLoader,
            taskRepository = taskRepository,
            featureRepository = featureRepository,
            projectRepository = projectRepository,
            statusValidator = statusValidator
        )

        // Setup default flow config (used by most tests)
        every { workflowConfigLoader.getFlowMappings() } returns createDefaultFlowMappings()
        every { workflowConfigLoader.getFlowConfig("default_flow") } returns createDefaultFlowConfig()
    }

    // ============================================================================
    // HAPPY PATH TESTS - first_task_started Event
    // ============================================================================

    @Test
    fun `detectCascadeEvents detects first_task_started when first task moves to in-progress`() = runBlocking {
        // Given: Task moving to in-progress, feature at planning status
        val task = createTask(
            id = testTaskId,
            featureId = testFeatureId,
            status = TaskStatus.IN_PROGRESS
        )
        val feature = createFeature(
            id = testFeatureId,
            status = FeatureStatus.PLANNING,
            tags = emptyList()
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        every { taskRepository.findByFeatureId(testFeatureId) } returns listOf(task)

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(1, events.size, "Should detect first_task_started event")

        val event = events[0]
        assertEquals("first_task_started", event.event)
        assertEquals(ContainerType.FEATURE, event.targetType)
        assertEquals(testFeatureId, event.targetId)
        assertEquals("planning", event.currentStatus)
        assertEquals("in-development", event.suggestedStatus)
        assertEquals("default_flow", event.flow)
        assertTrue(event.automatic)
        assertTrue(event.reason.contains("First task started"))
    }

    @Test
    fun `detectCascadeEvents does not detect first_task_started when feature already in-development`() = runBlocking {
        // Given: Task moving to in-progress, but feature already in-development
        val task = createTask(
            id = testTaskId,
            featureId = testFeatureId,
            status = TaskStatus.IN_PROGRESS
        )
        val feature = createFeature(
            id = testFeatureId,
            status = FeatureStatus.IN_DEVELOPMENT,
            tags = emptyList()
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        every { taskRepository.findByFeatureId(testFeatureId) } returns listOf(task)

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(0, events.size, "Should not detect first_task_started when feature already started")
    }

    @Test
    fun `detectCascadeEvents does not detect first_task_started when second task starts`() = runBlocking {
        // Given: Second task moving to in-progress
        val task1 = createTask(id = UUID.randomUUID(), featureId = testFeatureId, status = TaskStatus.IN_PROGRESS)
        val task2 = createTask(id = testTaskId, featureId = testFeatureId, status = TaskStatus.IN_PROGRESS)
        val feature = createFeature(id = testFeatureId, status = FeatureStatus.PLANNING, tags = emptyList())

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task2)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        every { taskRepository.findByFeatureId(testFeatureId) } returns listOf(task1, task2)

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(0, events.size, "Should not detect first_task_started for second task")
    }

    // ============================================================================
    // HAPPY PATH TESTS - all_tasks_complete Event
    // ============================================================================

    @Test
    fun `detectCascadeEvents detects all_tasks_complete when last task completes`() = runBlocking {
        // Given: Last task completing, all tasks done
        val task = createTask(
            id = testTaskId,
            featureId = testFeatureId,
            status = TaskStatus.COMPLETED
        )
        val feature = createFeature(
            id = testFeatureId,
            status = FeatureStatus.IN_DEVELOPMENT,
            tags = emptyList()
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        every { featureRepository.getTaskCountsByFeatureId(testFeatureId) } returns TaskCounts(
            total = 3,
            pending = 0,
            inProgress = 0,
            completed = 3,
            cancelled = 0,
            testing = 0,
            blocked = 0
        )

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(1, events.size, "Should detect all_tasks_complete event")

        val event = events[0]
        assertEquals("all_tasks_complete", event.event)
        assertEquals(ContainerType.FEATURE, event.targetType)
        assertEquals(testFeatureId, event.targetId)
        assertEquals("in-development", event.currentStatus)
        assertEquals("testing", event.suggestedStatus)
        assertEquals("default_flow", event.flow)
        assertTrue(event.automatic)
        assertTrue(event.reason.contains("3 tasks"))
    }

    @Test
    fun `detectCascadeEvents handles completed and cancelled tasks as done`() = runBlocking {
        // Given: Mix of completed and cancelled tasks
        val task = createTask(
            id = testTaskId,
            featureId = testFeatureId,
            status = TaskStatus.CANCELLED
        )
        val feature = createFeature(
            id = testFeatureId,
            status = FeatureStatus.IN_DEVELOPMENT,
            tags = emptyList()
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        every { featureRepository.getTaskCountsByFeatureId(testFeatureId) } returns TaskCounts(
            total = 4,
            pending = 0,
            inProgress = 0,
            completed = 2,
            cancelled = 2,
            testing = 0,
            blocked = 0
        )

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(1, events.size, "Should detect all_tasks_complete with mixed completed/cancelled")
        assertEquals("all_tasks_complete", events[0].event)
    }

    @Test
    fun `detectCascadeEvents does not detect all_tasks_complete when tasks remain`() = runBlocking {
        // Given: Task completing, but others still pending
        val task = createTask(
            id = testTaskId,
            featureId = testFeatureId,
            status = TaskStatus.COMPLETED
        )
        val feature = createFeature(
            id = testFeatureId,
            status = FeatureStatus.IN_DEVELOPMENT,
            tags = emptyList()
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        every { featureRepository.getTaskCountsByFeatureId(testFeatureId) } returns TaskCounts(
            total = 3,
            pending = 1,
            inProgress = 1,
            completed = 1,
            cancelled = 0,
            testing = 0,
            blocked = 0
        )

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(0, events.size, "Should not detect all_tasks_complete when tasks remain")
    }

    // ============================================================================
    // HAPPY PATH TESTS - all_features_complete Event
    // ============================================================================

    @Test
    fun `detectCascadeEvents detects all_features_complete when last feature completes`() = runBlocking {
        // Given: Last feature completing
        val feature = createFeature(
            id = testFeatureId,
            projectId = testProjectId,
            status = FeatureStatus.COMPLETED,
            tags = emptyList()
        )
        val project = createProject(
            id = testProjectId,
            status = ProjectStatus.IN_DEVELOPMENT
        )

        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        coEvery { projectRepository.getById(testProjectId) } returns Result.Success(project)
        every { projectRepository.getFeatureCountsByProjectId(testProjectId) } returns FeatureCounts(
            total = 2,
            completed = 2
        )

        // When
        val events = workflowService.detectCascadeEvents(testFeatureId, ContainerType.FEATURE)

        // Then
        assertEquals(1, events.size, "Should detect all_features_complete event")

        val event = events[0]
        assertEquals("all_features_complete", event.event)
        assertEquals(ContainerType.PROJECT, event.targetType)
        assertEquals(testProjectId, event.targetId)
        assertEquals("in-development", event.currentStatus)
        assertEquals("completed", event.suggestedStatus)
        assertEquals("default_flow", event.flow)
        assertEquals(true, event.automatic)
        assertTrue(event.reason.contains("2 features"))
    }

    @Test
    fun `detectCascadeEvents does not detect all_features_complete when features remain`() = runBlocking {
        // Given: Feature completing, but others in progress
        val feature = createFeature(
            id = testFeatureId,
            projectId = testProjectId,
            status = FeatureStatus.COMPLETED,
            tags = emptyList()
        )
        val project = createProject(
            id = testProjectId,
            status = ProjectStatus.IN_DEVELOPMENT
        )

        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        coEvery { projectRepository.getById(testProjectId) } returns Result.Success(project)
        every { projectRepository.getFeatureCountsByProjectId(testProjectId) } returns FeatureCounts(
            total = 3,
            completed = 1
        )

        // When
        val events = workflowService.detectCascadeEvents(testFeatureId, ContainerType.FEATURE)

        // Then
        assertEquals(0, events.size, "Should not detect all_features_complete when features remain")
    }

    @Test
    fun `detectCascadeEvents does not detect all_features_complete for already completed project`() = runBlocking {
        // Given: Feature completing in already completed project
        val feature = createFeature(
            id = testFeatureId,
            projectId = testProjectId,
            status = FeatureStatus.COMPLETED,
            tags = emptyList()
        )
        val project = createProject(
            id = testProjectId,
            status = ProjectStatus.COMPLETED
        )

        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        coEvery { projectRepository.getById(testProjectId) } returns Result.Success(project)
        every { projectRepository.getFeatureCountsByProjectId(testProjectId) } returns FeatureCounts(
            total = 2,
            completed = 2
        )

        // When
        val events = workflowService.detectCascadeEvents(testFeatureId, ContainerType.FEATURE)

        // Then
        assertEquals(0, events.size, "Should not suggest completion for already completed project")
    }

    // ============================================================================
    // HAPPY PATH TESTS - Flow Determination
    // ============================================================================

    @Test
    fun `determineActiveFlow returns default_flow for empty tags`() = runBlocking {
        // When
        val flow = workflowService.determineActiveFlow(emptyList())

        // Then
        assertEquals("default_flow", flow)
    }

    @Test
    fun `determineActiveFlow returns rapid_prototype_flow for prototype tag`() = runBlocking {
        // When
        val flow = workflowService.determineActiveFlow(listOf("prototype"))

        // Then
        assertEquals("rapid_prototype_flow", flow)
    }

    @Test
    fun `determineActiveFlow returns with_review_flow for security tag`() = runBlocking {
        // When
        val flow = workflowService.determineActiveFlow(listOf("security"))

        // Then
        assertEquals("with_review_flow", flow)
    }

    @Test
    fun `determineActiveFlow matches first matching flow when multiple tags`() = runBlocking {
        // When: Tags match multiple flows
        val flow = workflowService.determineActiveFlow(listOf("backend", "prototype", "security"))

        // Then: Should return first match (rapid_prototype_flow comes before with_review_flow in config)
        assertEquals("rapid_prototype_flow", flow)
    }

    @Test
    fun `determineActiveFlow returns default_flow for unrecognized tags`() = runBlocking {
        // When
        val flow = workflowService.determineActiveFlow(listOf("unknown", "custom"))

        // Then
        assertEquals("default_flow", flow)
    }

    // ============================================================================
    // HAPPY PATH TESTS - Config-Driven Behavior
    // ============================================================================

    @Test
    fun `detectCascadeEvents uses rapid_prototype_flow for feature with prototype tag`() = runBlocking {
        // Given: Feature with prototype tag, using rapid flow (skips testing)
        val task = createTask(
            id = testTaskId,
            featureId = testFeatureId,
            status = TaskStatus.COMPLETED
        )
        val feature = createFeature(
            id = testFeatureId,
            status = FeatureStatus.IN_DEVELOPMENT,
            tags = listOf("prototype")
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        every { featureRepository.getTaskCountsByFeatureId(testFeatureId) } returns TaskCounts(
            total = 1,
            pending = 0,
            inProgress = 0,
            completed = 1,
            cancelled = 0,
            testing = 0,
            blocked = 0
        )
        every { workflowConfigLoader.getFlowConfig("rapid_prototype_flow") } returns createRapidPrototypeFlowConfig()

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("rapid_prototype_flow", event.flow)
        assertEquals("completed", event.suggestedStatus, "Rapid flow should skip testing and go to completed")
    }

    @Test
    fun `detectCascadeEvents uses with_review_flow for feature with security tag`() = runBlocking {
        // Given: Feature with security tag
        val task = createTask(
            id = testTaskId,
            featureId = testFeatureId,
            status = TaskStatus.IN_PROGRESS
        )
        val feature = createFeature(
            id = testFeatureId,
            status = FeatureStatus.PLANNING,
            tags = listOf("security")
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        every { taskRepository.findByFeatureId(testFeatureId) } returns listOf(task)
        every { workflowConfigLoader.getFlowConfig("with_review_flow") } returns createWithReviewFlowConfig()

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(1, events.size)
        assertEquals("with_review_flow", events[0].flow)
    }

    // ============================================================================
    // ERROR PATH TESTS - Missing Entities
    // ============================================================================

    @Test
    fun `detectCascadeEvents returns empty list when task not found`() = runBlocking {
        // Given: Task doesn't exist
        coEvery { taskRepository.getById(testTaskId) } returns Result.Error(
            RepositoryError.NotFound(testTaskId, EntityType.TASK, "Not found")
        )

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(0, events.size, "Should return empty list for missing task")
    }

    @Test
    fun `detectCascadeEvents returns empty list when task has no feature`() = runBlocking {
        // Given: Task with no featureId
        val task = createTask(
            id = testTaskId,
            featureId = null,
            status = TaskStatus.COMPLETED
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(0, events.size, "Should return empty list for task without feature")
    }

    @Test
    fun `detectCascadeEvents returns empty list when feature not found`() = runBlocking {
        // Given: Task exists but feature doesn't
        val task = createTask(
            id = testTaskId,
            featureId = testFeatureId,
            status = TaskStatus.COMPLETED
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Error(
            RepositoryError.NotFound(testFeatureId, EntityType.FEATURE, "Not found")
        )

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(0, events.size, "Should return empty list when feature not found")
    }

    @Test
    fun `detectCascadeEvents returns empty list when feature has no project`() = runBlocking {
        // Given: Feature with no projectId
        val feature = createFeature(
            id = testFeatureId,
            projectId = null,
            status = FeatureStatus.COMPLETED,
            tags = emptyList()
        )

        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)

        // When
        val events = workflowService.detectCascadeEvents(testFeatureId, ContainerType.FEATURE)

        // Then
        assertEquals(0, events.size, "Should return empty list for feature without project")
    }

    @Test
    fun `detectCascadeEvents returns empty list when project not found`() = runBlocking {
        // Given: Feature exists but project doesn't
        val feature = createFeature(
            id = testFeatureId,
            projectId = testProjectId,
            status = FeatureStatus.COMPLETED,
            tags = emptyList()
        )

        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        coEvery { projectRepository.getById(testProjectId) } returns Result.Error(
            RepositoryError.NotFound(testProjectId, EntityType.PROJECT, "Not found")
        )

        // When
        val events = workflowService.detectCascadeEvents(testFeatureId, ContainerType.FEATURE)

        // Then
        assertEquals(0, events.size, "Should return empty list when project not found")
    }

    @Test
    fun `detectCascadeEvents returns empty list for project container type`() = runBlocking {
        // Given: Project as container type

        // When
        val events = workflowService.detectCascadeEvents(testProjectId, ContainerType.PROJECT)

        // Then
        assertEquals(0, events.size, "Projects are top-level, no cascades upward")
    }

    // ============================================================================
    // ERROR PATH TESTS - Edge Cases
    // ============================================================================

    @Test
    fun `detectCascadeEvents handles feature with zero tasks`() = runBlocking {
        // Given: Task completing in feature, but task counts show zero total (edge case)
        val task = createTask(
            id = testTaskId,
            featureId = testFeatureId,
            status = TaskStatus.COMPLETED
        )
        val feature = createFeature(
            id = testFeatureId,
            status = FeatureStatus.IN_DEVELOPMENT,
            tags = emptyList()
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        every { featureRepository.getTaskCountsByFeatureId(testFeatureId) } returns TaskCounts(
            total = 0,
            pending = 0,
            inProgress = 0,
            completed = 0,
            cancelled = 0,
            testing = 0,
            blocked = 0
        )

        // When
        val events = workflowService.detectCascadeEvents(testTaskId, ContainerType.TASK)

        // Then
        assertEquals(0, events.size, "Should not detect all_tasks_complete when total is zero")
    }

    @Test
    fun `detectCascadeEvents handles project with zero features`() = runBlocking {
        // Given: Feature completing, but project has zero features (edge case)
        val feature = createFeature(
            id = testFeatureId,
            projectId = testProjectId,
            status = FeatureStatus.COMPLETED,
            tags = emptyList()
        )
        val project = createProject(
            id = testProjectId,
            status = ProjectStatus.IN_DEVELOPMENT
        )

        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        coEvery { projectRepository.getById(testProjectId) } returns Result.Success(project)
        every { projectRepository.getFeatureCountsByProjectId(testProjectId) } returns FeatureCounts(
            total = 0,
            completed = 0
        )

        // When
        val events = workflowService.detectCascadeEvents(testFeatureId, ContainerType.FEATURE)

        // Then
        assertEquals(0, events.size, "Should not detect all_features_complete when total is zero")
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    private fun createTask(
        id: UUID,
        featureId: UUID?,
        status: TaskStatus
    ): Task {
        return Task(
            id = id,
            title = "Test Task",
            summary = "",
            status = status,
            priority = Priority.MEDIUM,
            complexity = 5,
            projectId = testProjectId,
            featureId = featureId,
            tags = emptyList()
        )
    }

    private fun createFeature(
        id: UUID,
        projectId: UUID? = testProjectId,
        status: FeatureStatus,
        tags: List<String>
    ): Feature {
        return Feature(
            id = id,
            name = "Test Feature",
            summary = "",
            description = null,
            status = status,
            priority = Priority.MEDIUM,
            projectId = projectId,
            tags = tags
        )
    }

    private fun createProject(
        id: UUID,
        status: ProjectStatus
    ): Project {
        return Project(
            id = id,
            name = "Test Project",
            summary = "",
            description = null,
            status = status,
            tags = emptyList()
        )
    }

    private fun createDefaultFlowMappings(): Map<String, io.github.jpicklyk.mcptask.domain.model.workflow.FlowConfig> {
        return mapOf(
            "default_flow" to createDefaultFlowConfig(),
            "rapid_prototype_flow" to createRapidPrototypeFlowConfig(),
            "with_review_flow" to createWithReviewFlowConfig()
        )
    }

    private fun createDefaultFlowConfig(): io.github.jpicklyk.mcptask.domain.model.workflow.FlowConfig {
        return io.github.jpicklyk.mcptask.domain.model.workflow.FlowConfig(
            name = "default_flow",
            statuses = listOf("planning", "in-development", "testing", "validating", "completed"),
            tags = null,
            eventHandlers = mapOf(
                "first_task_started" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "planning",
                    to = "in-development",
                    auto = true
                ),
                "all_tasks_complete" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "in-development",
                    to = "testing",
                    auto = true
                ),
                "tests_passed" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "testing",
                    to = "validating",
                    auto = true
                ),
                "completion_requested" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "validating",
                    to = "completed",
                    auto = false
                )
            )
        )
    }

    private fun createRapidPrototypeFlowConfig(): io.github.jpicklyk.mcptask.domain.model.workflow.FlowConfig {
        return io.github.jpicklyk.mcptask.domain.model.workflow.FlowConfig(
            name = "rapid_prototype_flow",
            statuses = listOf("planning", "in-development", "completed"),
            tags = listOf("prototype", "spike", "experiment"),
            eventHandlers = mapOf(
                "first_task_started" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "planning",
                    to = "in-development",
                    auto = true
                ),
                "all_tasks_complete" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "in-development",
                    to = "completed",
                    auto = true
                )
            )
        )
    }

    private fun createWithReviewFlowConfig(): io.github.jpicklyk.mcptask.domain.model.workflow.FlowConfig {
        return io.github.jpicklyk.mcptask.domain.model.workflow.FlowConfig(
            name = "with_review_flow",
            statuses = listOf("planning", "in-development", "testing", "validating", "pending-review", "completed"),
            tags = listOf("security", "compliance", "audit"),
            eventHandlers = mapOf(
                "first_task_started" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "planning",
                    to = "in-development",
                    auto = true
                ),
                "all_tasks_complete" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "in-development",
                    to = "testing",
                    auto = true
                ),
                "tests_passed" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "testing",
                    to = "validating",
                    auto = true
                ),
                "completion_requested" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "validating",
                    to = "pending-review",
                    auto = true
                ),
                "review_approved" to io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler(
                    from = "pending-review",
                    to = "completed",
                    auto = false
                )
            )
        )
    }
}
