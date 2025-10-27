package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteFeatureRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteProjectRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTaskRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for complete workflow scenarios.
 *
 * These tests verify:
 * - End-to-end cascade detection with real database
 * - Config-driven status transitions
 * - Multiple container types interacting
 * - WorkflowService integration with repositories
 * - Prerequisite validation in real scenarios
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowIntegrationTest {

    private lateinit var db: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var taskRepository: SQLiteTaskRepository
    private lateinit var featureRepository: SQLiteFeatureRepository
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var workflowService: WorkflowService
    private lateinit var workflowConfigLoader: WorkflowConfigLoader
    private lateinit var statusValidator: StatusValidator

    @BeforeAll
    fun setUp() {
        // Connect directly to H2 in-memory database
        db = Database.connect(
            url = "jdbc:h2:mem:workflowintegrationtest;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )

        // Set transaction isolation level
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // Initialize database manager and repositories
        databaseManager = DatabaseManager(db)
        databaseManager.updateSchema()
        taskRepository = SQLiteTaskRepository(databaseManager)
        featureRepository = SQLiteFeatureRepository(databaseManager)
        projectRepository = SQLiteProjectRepository(databaseManager)

        // Initialize workflow components
        statusValidator = StatusValidator()
        workflowConfigLoader = WorkflowConfigLoaderImpl()
        workflowService = WorkflowServiceImpl(
            workflowConfigLoader = workflowConfigLoader,
            taskRepository = taskRepository,
            featureRepository = featureRepository,
            projectRepository = projectRepository,
            statusValidator = statusValidator
        )
    }

    @AfterAll
    fun tearDown() {
        // Note: No need to drop tables for in-memory database
    }

    // ============================================================================
    // FEATURE WORKFLOW INTEGRATION TESTS
    // ============================================================================

    @Test
    fun `complete feature workflow - first task starts feature development`() = runBlocking {
        // Given: Project and feature in planning
        val projectId = createProject("Test Project", ProjectStatus.PLANNING)
        val featureId = createFeature("Feature A", projectId, FeatureStatus.PLANNING)
        val taskId = createTask("Task 1", featureId, projectId, TaskStatus.PENDING)

        // When: First task starts
        updateTaskStatus(taskId, TaskStatus.IN_PROGRESS)

        // Then: Cascade event detected for feature
        val cascadeEvents = workflowService.detectCascadeEvents(taskId, ContainerType.TASK)
        assertEquals(1, cascadeEvents.size)

        val event = cascadeEvents.first()
        assertEquals("first_task_started", event.event)
        assertEquals(ContainerType.FEATURE, event.targetType)
        assertEquals(featureId, event.targetId)
        assertEquals("planning", event.currentStatus)
        assertEquals("in-development", event.suggestedStatus)
        assertTrue(event.automatic)
    }

    @Test
    fun `complete feature workflow - all tasks complete triggers testing`() = runBlocking {
        // Given: Feature in development with multiple tasks
        val projectId = createProject("Test Project", ProjectStatus.IN_DEVELOPMENT)
        val featureId = createFeature("Feature B", projectId, FeatureStatus.IN_DEVELOPMENT)
        val task1Id = createTask("Task 1", featureId, projectId, TaskStatus.COMPLETED)
        val task2Id = createTask("Task 2", featureId, projectId, TaskStatus.COMPLETED)
        val task3Id = createTask("Task 3", featureId, projectId, TaskStatus.IN_PROGRESS)

        // When: Last task completes
        updateTaskStatus(task3Id, TaskStatus.COMPLETED)

        // Then: Cascade event detected for feature
        val cascadeEvents = workflowService.detectCascadeEvents(task3Id, ContainerType.TASK)
        assertEquals(1, cascadeEvents.size)

        val event = cascadeEvents.first()
        assertEquals("all_tasks_complete", event.event)
        assertEquals(ContainerType.FEATURE, event.targetType)
        assertEquals(featureId, event.targetId)
        assertEquals("in-development", event.currentStatus)
        assertEquals("testing", event.suggestedStatus)
        assertTrue(event.automatic)
    }

    @Test
    fun `rapid prototype flow - skips testing phase`() = runBlocking {
        // Given: Feature with rapid prototype tags
        val projectId = createProject("Prototype Project", ProjectStatus.IN_DEVELOPMENT)
        val featureId = createFeature(
            name = "Spike Feature",
            projectId = projectId,
            status = FeatureStatus.IN_DEVELOPMENT,
            tags = "prototype,spike"
        )
        val taskId = createTask("Prototype Task", featureId, projectId, TaskStatus.IN_PROGRESS)

        // When: Task completes
        updateTaskStatus(taskId, TaskStatus.COMPLETED)

        // Then: Cascade event suggests going directly to completed
        val cascadeEvents = workflowService.detectCascadeEvents(taskId, ContainerType.TASK)
        assertEquals(1, cascadeEvents.size)

        val event = cascadeEvents.first()
        assertEquals("all_tasks_complete", event.event)
        assertEquals("rapid_prototype_flow", event.flow)
        assertEquals("in-development", event.currentStatus)
        assertEquals("completed", event.suggestedStatus) // Skips testing
        assertTrue(event.automatic)
    }

    @Test
    fun `with review flow - adds review gate before completion`() = runBlocking {
        // Given: Feature with review tags
        val projectId = createProject("Secure Project", ProjectStatus.IN_DEVELOPMENT)
        val featureId = createFeature(
            name = "Security Feature",
            projectId = projectId,
            status = FeatureStatus.TESTING,
            tags = "security,compliance"
        )

        // When: Checking workflow state
        val workflowState = workflowService.getWorkflowState(featureId, ContainerType.FEATURE)

        // Then: Workflow uses with_review_flow
        assertEquals("with_review_flow", workflowState.activeFlow)
        assertTrue(workflowState.allowedTransitions.contains("pending-review"))

        // Verify prerequisites show review requirement
        val testingPrereqs = workflowState.prerequisites["validating"]
        assertNotNull(testingPrereqs)
        assertTrue(testingPrereqs.requirements.isNotEmpty())
    }

    // ============================================================================
    // PROJECT WORKFLOW INTEGRATION TESTS
    // ============================================================================

    @Test
    fun `project workflow - all features complete triggers project completion`() = runBlocking {
        // Given: Project with multiple features
        val projectId = createProject("Complete Project", ProjectStatus.IN_DEVELOPMENT)
        val feature1Id = createFeature("Feature 1", projectId, FeatureStatus.COMPLETED)
        val feature2Id = createFeature("Feature 2", projectId, FeatureStatus.COMPLETED)
        val feature3Id = createFeature("Feature 3", projectId, FeatureStatus.TESTING)

        // When: Last feature completes
        updateFeatureStatus(feature3Id, FeatureStatus.COMPLETED)

        // Then: Cascade event detected for project
        val cascadeEvents = workflowService.detectCascadeEvents(feature3Id, ContainerType.FEATURE)
        assertEquals(1, cascadeEvents.size)

        val event = cascadeEvents.first()
        assertEquals("all_features_complete", event.event)
        assertEquals(ContainerType.PROJECT, event.targetType)
        assertEquals(projectId, event.targetId)
        assertEquals("in-development", event.currentStatus)
        assertEquals("completed", event.suggestedStatus)
        assertTrue(event.automatic)
    }

    // ============================================================================
    // WORKFLOW STATE QUERY INTEGRATION TESTS
    // ============================================================================

    @Test
    fun `getWorkflowState returns complete state with prerequisites`() = runBlocking {
        // Given: Feature with tasks in various states
        val projectId = createProject("State Project", ProjectStatus.IN_DEVELOPMENT)
        val featureId = createFeature("Feature C", projectId, FeatureStatus.IN_DEVELOPMENT)
        createTask("Task 1", featureId, projectId, TaskStatus.COMPLETED)
        createTask("Task 2", featureId, projectId, TaskStatus.IN_PROGRESS)

        // When: Getting workflow state
        val workflowState = workflowService.getWorkflowState(featureId, ContainerType.FEATURE)

        // Then: State includes current status, flow, and transitions
        assertEquals("in-development", workflowState.currentStatus)
        assertEquals("default_flow", workflowState.activeFlow)
        assertTrue(workflowState.allowedTransitions.isNotEmpty())

        // Verify prerequisites are populated
        assertNotNull(workflowState.prerequisites)
        val testingPrereqs = workflowState.prerequisites["testing"]
        assertNotNull(testingPrereqs)
        assertEquals(false, testingPrereqs.met) // Not all tasks complete yet
        assertTrue(testingPrereqs.blockingReasons.isNotEmpty())
    }

    @Test
    fun `getWorkflowState detects pending cascade events`() = runBlocking {
        // Given: Feature with all tasks completed
        val projectId = createProject("Cascade Project", ProjectStatus.IN_DEVELOPMENT)
        val featureId = createFeature("Feature D", projectId, FeatureStatus.IN_DEVELOPMENT)
        createTask("Task 1", featureId, projectId, TaskStatus.COMPLETED)
        createTask("Task 2", featureId, projectId, TaskStatus.COMPLETED)

        // When: Getting workflow state
        val workflowState = workflowService.getWorkflowState(featureId, ContainerType.FEATURE)

        // Then: Detected events include all_tasks_complete
        assertEquals(1, workflowState.detectedEvents.size)

        val event = workflowState.detectedEvents.first()
        assertEquals("all_tasks_complete", event.event)
        assertEquals(featureId, event.targetId)
        assertEquals("in-development", event.currentStatus)
        assertEquals("testing", event.suggestedStatus)
    }

    // ============================================================================
    // CONFIG-DRIVEN BEHAVIOR TESTS
    // ============================================================================

    @Test
    fun `workflow respects config-defined status progressions`() = runBlocking {
        // Given: Default config loaded
        val progressions = workflowConfigLoader.getStatusProgressions()

        // Then: All entity types have defined progressions
        assertTrue(progressions.containsKey("feature"))
        assertTrue(progressions.containsKey("task"))
        assertTrue(progressions.containsKey("project"))

        // Verify feature progression matches default_flow
        val featureProgression = progressions["feature"]!!
        assertTrue(featureProgression.contains("planning"))
        assertTrue(featureProgression.contains("in-development"))
        assertTrue(featureProgression.contains("testing"))
        assertTrue(featureProgression.contains("completed"))
    }

    @Test
    fun `event handlers are config-driven and flow-specific`() {
        // When: Loading event handlers for different flows
        val defaultHandlers = workflowConfigLoader.getEventHandlers("default_flow")
        val rapidHandlers = workflowConfigLoader.getEventHandlers("rapid_prototype_flow")
        val reviewHandlers = workflowConfigLoader.getEventHandlers("with_review_flow")

        // Then: Different flows have different event handlers
        assertTrue(defaultHandlers.containsKey("tests_passed"))
        assertTrue(rapidHandlers.containsKey("all_tasks_complete"))
        assertTrue(reviewHandlers.containsKey("review_approved"))

        // Verify default flow has completion gate requiring confirmation
        val completionHandler = defaultHandlers["completion_requested"]!!
        assertEquals(false, completionHandler.auto)

        // Verify rapid flow auto-completes
        val rapidCompletion = rapidHandlers["all_tasks_complete"]!!
        assertEquals("completed", rapidCompletion.to)
    }

    // ============================================================================
    // MULTI-CONTAINER INTERACTION TESTS
    // ============================================================================

    @Test
    fun `cascading events propagate through project hierarchy`() = runBlocking {
        // Given: Complete project hierarchy
        val projectId = createProject("Hierarchy Project", ProjectStatus.IN_DEVELOPMENT)
        val featureId = createFeature("Feature E", projectId, FeatureStatus.IN_DEVELOPMENT)
        val taskId = createTask("Final Task", featureId, projectId, TaskStatus.IN_PROGRESS)

        // When: Task completes (last task in feature, last feature in project)
        updateTaskStatus(taskId, TaskStatus.COMPLETED)

        // Then: Multiple cascade events detected
        val cascadeEvents = workflowService.detectCascadeEvents(taskId, ContainerType.TASK)
        assertTrue(cascadeEvents.size >= 1)

        // First event: Feature all_tasks_complete
        val featureEvent = cascadeEvents.find { it.targetType == ContainerType.FEATURE }
        assertNotNull(featureEvent)
        assertEquals("all_tasks_complete", featureEvent.event)
        assertEquals(featureId, featureEvent.targetId)

        // Check if project cascade would trigger (depends on other features)
        val projectCascades = workflowService.detectCascadeEvents(featureId, ContainerType.FEATURE)
        // Would only trigger if this is the last feature
    }

    // ============================================================================
    // VALIDATION AND PREREQUISITE TESTS
    // ============================================================================

    @Test
    fun `prerequisite validation prevents invalid transitions`() = runBlocking {
        // Given: Feature with incomplete tasks
        val projectId = createProject("Validation Project", ProjectStatus.IN_DEVELOPMENT)
        val featureId = createFeature("Feature F", projectId, FeatureStatus.IN_DEVELOPMENT)
        createTask("Task 1", featureId, projectId, TaskStatus.COMPLETED)
        createTask("Task 2", featureId, projectId, TaskStatus.IN_PROGRESS)

        // When: Getting workflow state
        val workflowState = workflowService.getWorkflowState(featureId, ContainerType.FEATURE)

        // Then: Testing transition shows unmet prerequisites
        val testingPrereqs = workflowState.prerequisites["testing"]
        assertNotNull(testingPrereqs)
        assertEquals(false, testingPrereqs.met)
        assertTrue(testingPrereqs.blockingReasons.contains("Not all tasks completed"))
    }

    // ============================================================================
    // HELPER METHODS
    // ============================================================================

    private suspend fun createProject(name: String, status: ProjectStatus): UUID {
        val project = Project(
            id = UUID.randomUUID(),
            name = name,
            description = "Test project",
            status = status,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
        return when (val result = projectRepository.create(project)) {
            is Result.Success -> result.data.id
            is Result.Error -> error("Failed to create project: ${result.error}")
        }
    }

    private suspend fun createFeature(
        name: String,
        projectId: UUID,
        status: FeatureStatus,
        tags: String = ""
    ): UUID {
        val tagsList = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() }
        val feature = Feature(
            id = UUID.randomUUID(),
            projectId = projectId,
            name = name,
            summary = "Test feature",
            description = "Test feature description",
            status = status,
            priority = Priority.MEDIUM,
            tags = tagsList,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
        return when (val result = featureRepository.create(feature)) {
            is Result.Success -> result.data.id
            is Result.Error -> error("Failed to create feature: ${result.error}")
        }
    }

    private suspend fun createTask(
        title: String,
        featureId: UUID,
        projectId: UUID,
        status: TaskStatus
    ): UUID {
        val task = Task(
            id = UUID.randomUUID(),
            featureId = featureId,
            projectId = projectId,
            title = title,
            summary = "Test task summary",
            description = "Test task description",
            status = status,
            priority = Priority.MEDIUM,
            complexity = 3,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
        return when (val result = taskRepository.create(task)) {
            is Result.Success -> result.data.id
            is Result.Error -> error("Failed to create task: ${result.error}")
        }
    }

    private suspend fun updateTaskStatus(taskId: UUID, newStatus: TaskStatus) {
        val taskResult = taskRepository.getById(taskId)
        val task = when (taskResult) {
            is Result.Success -> taskResult.data
            is Result.Error -> error("Failed to get task: ${taskResult.error}")
        }

        val updated = task.copy(status = newStatus, modifiedAt = Instant.now())
        when (val result = taskRepository.update(updated)) {
            is Result.Success -> Unit
            is Result.Error -> error("Failed to update task: ${result.error}")
        }
    }

    private suspend fun updateFeatureStatus(featureId: UUID, newStatus: FeatureStatus) {
        val featureResult = featureRepository.getById(featureId)
        val feature = when (featureResult) {
            is Result.Success -> featureResult.data
            is Result.Error -> error("Failed to get feature: ${featureResult.error}")
        }

        val updated = feature.copy(status = newStatus, modifiedAt = Instant.now())
        when (val result = featureRepository.update(updated)) {
            is Result.Success -> Unit
            is Result.Error -> error("Failed to update feature: ${result.error}")
        }
    }
}
