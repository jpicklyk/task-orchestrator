package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Tests for StatusValidator service
 *
 * Tests both v1.0 (enum-based) and v2.0 (config-based) validation modes
 */
class StatusValidatorTest {

    private lateinit var validator: StatusValidator
    private lateinit var originalUserDir: String

    @BeforeEach
    fun setup() {
        validator = StatusValidator()
        originalUserDir = System.getProperty("user.dir")
    }

    @AfterEach
    fun tearDown() {
        System.setProperty("user.dir", originalUserDir)
    }

    // ========== V1.0 ENUM-BASED MODE TESTS ==========

    @Test
    fun `v1 mode - validates valid project status`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("planning", "project")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v1 mode - rejects invalid project status`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("invalid-status", "project")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        assertTrue((result as StatusValidator.ValidationResult.Invalid).reason.contains("Invalid status"))
    }

    @Test
    fun `v1 mode - validates valid feature status`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("in-development", "feature")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v1 mode - validates valid task status`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v1 mode - validates cancelled status for tasks`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("cancelled", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v1 mode - validates deferred status for tasks`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deferred", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v1 mode - transitions always allowed`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        // First check that both statuses are valid
        val completedValid = validator.validateStatus("completed", "task")
        assertTrue(completedValid is StatusValidator.ValidationResult.Valid, "completed should be valid")

        val inProgressValid = validator.validateStatus("in-progress", "task")
        assertTrue(inProgressValid is StatusValidator.ValidationResult.Valid, "in-progress should be valid")

        // In v1 mode, no transition validation - all valid transitions allowed
        val result = validator.validateTransition("completed", "in-progress", "task")
        if (result is StatusValidator.ValidationResult.Invalid) {
            fail("Transition should be valid in v1 mode. Reason: ${result.reason}")
        }
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v1 mode - getAllowedStatuses returns enum values`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val taskStatuses = validator.getAllowedStatuses("task")
        assertTrue(taskStatuses.contains("pending"))
        assertTrue(taskStatuses.contains("in-progress"))
        assertTrue(taskStatuses.contains("testing"))
        assertTrue(taskStatuses.contains("completed"))
        assertTrue(taskStatuses.contains("cancelled"))
        assertTrue(taskStatuses.contains("deferred"))
        assertTrue(taskStatuses.contains("blocked"))
    }

    // ========== V2.0 CONFIG-BASED MODE TESTS ==========

    @Test
    fun `v2 mode - validates status using config`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateStatus("validating", "feature")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - rejects status not in config`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateStatus("invalid-status", "feature")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
    }

    @Test
    fun `v2 mode - validates cancelled and deferred for tasks`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val cancelledResult = validator.validateStatus("cancelled", "task")
        assertTrue(cancelledResult is StatusValidator.ValidationResult.Valid)

        val deferredResult = validator.validateStatus("deferred", "task")
        assertTrue(deferredResult is StatusValidator.ValidationResult.Valid)
    }

    // ========== TRANSITION VALIDATION TESTS ==========

    @Test
    fun `v2 mode - allows forward transition in default flow`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("pending", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - allows backward transition when enabled`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("testing", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - blocks skipping statuses when sequential enforced`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("pending", "testing", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalidResult = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalidResult.reason.contains("skip"))
        assertTrue(invalidResult.suggestions.contains("in-progress"))
    }

    @Test
    fun `v2 mode - allows emergency transitions to blocked`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("in-progress", "blocked", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - blocks transition from terminal status`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("completed", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        assertTrue((result as StatusValidator.ValidationResult.Invalid).reason.contains("terminal"))
    }

    @Test
    fun `v2 mode - allows emergency transition to cancelled`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("in-progress", "cancelled", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - allows emergency transition to deferred`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("testing", "deferred", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - feature transitions work correctly`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        // Forward transition
        val forward = validator.validateTransition("planning", "in-development", "feature")
        assertTrue(forward is StatusValidator.ValidationResult.Valid)

        // Backward transition (allowed)
        val backward = validator.validateTransition("testing", "in-development", "feature")
        assertTrue(backward is StatusValidator.ValidationResult.Valid)

        // Emergency transition to blocked
        val emergency = validator.validateTransition("testing", "blocked", "feature")
        assertTrue(emergency is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - project transitions work correctly`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val forward = validator.validateTransition("planning", "in-development", "project")
        assertTrue(forward is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - getAllowedStatuses returns config values`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val taskStatuses = validator.getAllowedStatuses("task")
        assertTrue(taskStatuses.contains("pending"))
        assertTrue(taskStatuses.contains("in-progress"))
        assertTrue(taskStatuses.contains("testing"))
        assertTrue(taskStatuses.contains("blocked"))
        assertTrue(taskStatuses.contains("completed"))
        assertTrue(taskStatuses.contains("cancelled"))
        assertTrue(taskStatuses.contains("deferred"))
    }

    // ========== ENUM MEMBERSHIP VALIDATION TESTS (10 tests) ==========

    @Test
    fun `enum validation - accepts all valid task statuses`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        // TaskStatus enum values (v1.0 + v2.0)
        val validStatuses = listOf(
            "pending", "in-progress", "completed", "cancelled", "deferred",
            "backlog", "in-review", "changes-requested", "on-hold", "testing",
            "ready-for-qa", "investigating", "blocked", "deployed"
        )

        validStatuses.forEach { status ->
            val result = validator.validateStatus(status, "task")
            assertTrue(result is StatusValidator.ValidationResult.Valid || result is StatusValidator.ValidationResult.ValidWithAdvisory,
                "Expected $status to be valid for task, but got: ${if (result is StatusValidator.ValidationResult.Invalid) result.reason else "unknown"}")
        }
    }

    @Test
    fun `enum validation - accepts all valid feature statuses`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        // FeatureStatus enum values (v1.0 + v2.0)
        val validStatuses = listOf(
            "planning", "in-development", "completed", "archived",
            "draft", "on-hold", "testing", "validating", "pending-review", "blocked", "deployed"
        )

        validStatuses.forEach { status ->
            val result = validator.validateStatus(status, "feature")
            assertTrue(result is StatusValidator.ValidationResult.Valid || result is StatusValidator.ValidationResult.ValidWithAdvisory,
                "Expected $status to be valid for feature, but got: ${if (result is StatusValidator.ValidationResult.Invalid) result.reason else "unknown"}")
        }
    }

    @Test
    fun `enum validation - accepts all valid project statuses`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val validStatuses = listOf(
            "planning", "in-development", "completed",
            "archived", "on-hold", "cancelled"
        )

        validStatuses.forEach { status ->
            val result = validator.validateStatus(status, "project")
            assertTrue(result is StatusValidator.ValidationResult.Valid,
                "Expected $status to be valid for project, but got: ${if (result is StatusValidator.ValidationResult.Invalid) result.reason else "unknown"}")
        }
    }

    @Test
    fun `enum validation - rejects invalid status with clear error message`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("not-a-real-status", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("Invalid status"))
        assertTrue(invalid.reason.contains("not-a-real-status"))
        assertTrue(invalid.suggestions.isNotEmpty())
    }

    @Test
    fun `enum validation - case insensitive validation`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result1 = validator.validateStatus("IN_PROGRESS", "task")
        assertTrue(result1 is StatusValidator.ValidationResult.Valid)

        val result2 = validator.validateStatus("In-Progress", "task")
        assertTrue(result2 is StatusValidator.ValidationResult.Valid)

        val result3 = validator.validateStatus("in-progress", "task")
        assertTrue(result3 is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `enum validation - underscore to hyphen normalization`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result1 = validator.validateStatus("in_progress", "task")
        assertTrue(result1 is StatusValidator.ValidationResult.Valid)

        val result2 = validator.validateStatus("in-progress", "task")
        assertTrue(result2 is StatusValidator.ValidationResult.Valid)

        val result3 = validator.validateStatus("changes_requested", "task")
        assertTrue(result3 is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `enum validation - rejects status from different container type`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        // "validating" is a feature status, not a task status
        val result = validator.validateStatus("validating", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
    }

    @Test
    fun `enum validation - v2 mode accepts status in config`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithCustomStatus(tempDir, "custom-status")

        val result = validator.validateStatus("custom-status", "task")
        // In v2 mode with config, status is validated against config, not enum
        // This test shows config-based validation takes precedence
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `enum validation - provides all enum values as suggestions`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("invalid-status", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid

        assertTrue(invalid.suggestions.contains("pending"))
        assertTrue(invalid.suggestions.contains("in-progress"))
        assertTrue(invalid.suggestions.contains("completed"))
    }

    @Test
    fun `enum validation - handles unknown container type gracefully`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val statuses = validator.getAllowedStatuses("unknown-type")
        assertTrue(statuses.isEmpty())
    }

    // ========== CONFIG ENABLEMENT VALIDATION TESTS (5 tests) ==========

    @Test
    fun `config enablement - accepts enabled status`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateStatus("in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `config enablement - rejects disabled status with clear message`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithLimitedStatuses(tempDir)

        // "archived" is a valid enum value but not in allowed_statuses
        val result = validator.validateStatus("archived", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("Invalid status") || invalid.reason.contains("Allowed statuses"))
    }

    @Test
    fun `config enablement - missing config falls back to enum validation`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        // No config created - should use enum validation

        val result = validator.validateStatus("in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `config enablement - statuses are derived from flows`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithEmptyAllowedStatuses(tempDir)

        // In v2, allowed_statuses is derived from flows (not explicitly configured)
        // The config has default_flow: [pending, in-progress, completed]
        val result = validator.validateStatus("in-progress", "task")
        // in-progress is in the default_flow, so it should be valid
        assertTrue(result is StatusValidator.ValidationResult.Valid || result is StatusValidator.ValidationResult.ValidWithAdvisory)
    }

    @Test
    fun `config enablement - getAllowedStatuses respects config`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithLimitedStatuses(tempDir)

        val statuses = validator.getAllowedStatuses("task")
        assertTrue(statuses.contains("pending"))
        assertTrue(statuses.contains("in-progress"))
        assertFalse(statuses.contains("archived")) // Not in config
    }

    // ========== PREREQUISITE VALIDATION TESTS (15 tests) ==========

    @Test
    fun `prerequisite - feature IN_DEVELOPMENT requires at least 1 task`() = runBlocking {
        val featureId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        coEvery { mockFeatureRepo.getTaskCount(featureId) } returns Result.Success(0)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(featureId, "in-development", "feature", context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("at least 1 task"))
    }

    @Test
    fun `prerequisite - feature IN_DEVELOPMENT succeeds with tasks`() = runBlocking {
        val featureId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        coEvery { mockFeatureRepo.getTaskCount(featureId) } returns Result.Success(3)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(featureId, "in-development", "feature", context)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `prerequisite - feature TESTING requires all tasks completed`() = runBlocking {
        val featureId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val incompleteTasks = listOf(
            createMockTask(UUID.randomUUID(), "Task 1", TaskStatus.IN_PROGRESS),
            createMockTask(UUID.randomUUID(), "Task 2", TaskStatus.COMPLETED)
        )

        coEvery { mockTaskRepo.findByFeature(featureId, null, null, 1000) } returns Result.Success(incompleteTasks)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(featureId, "testing", "feature", context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("not completed"))
        assertTrue(invalid.reason.contains("Task 1"))
    }

    @Test
    fun `prerequisite - feature TESTING succeeds when all tasks completed`() = runBlocking {
        val featureId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val completedTasks = listOf(
            createMockTask(UUID.randomUUID(), "Task 1", TaskStatus.COMPLETED),
            createMockTask(UUID.randomUUID(), "Task 2", TaskStatus.COMPLETED)
        )

        coEvery { mockTaskRepo.findByFeature(featureId, null, null, 1000) } returns Result.Success(completedTasks)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(featureId, "testing", "feature", context)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `prerequisite - feature COMPLETED requires all tasks completed`() = runBlocking {
        val featureId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val mixedTasks = listOf(
            createMockTask(UUID.randomUUID(), "Task 1", TaskStatus.COMPLETED),
            createMockTask(UUID.randomUUID(), "Task 2", TaskStatus.TESTING),
            createMockTask(UUID.randomUUID(), "Task 3", TaskStatus.COMPLETED)
        )

        coEvery { mockTaskRepo.findByFeature(featureId, null, null, 1000) } returns Result.Success(mixedTasks)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(featureId, "completed", "feature", context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("1 task(s) not completed"))
    }

    @Test
    fun `prerequisite - task IN_PROGRESS blocked by incomplete dependencies`() = runBlocking {
        val taskId = UUID.randomUUID()
        val blockerId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val blockingDep = Dependency(
            id = UUID.randomUUID(),
            fromTaskId = blockerId,
            toTaskId = taskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val blockerTask = createMockTask(blockerId, "Blocker Task", TaskStatus.IN_PROGRESS)

        coEvery { mockDependencyRepo.findByToTaskId(taskId) } returns listOf(blockingDep)
        coEvery { mockTaskRepo.getById(blockerId) } returns Result.Success(blockerTask)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(taskId, "in-progress", "task", context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("blocked by"))
        assertTrue(invalid.reason.contains("Blocker Task"))
    }

    @Test
    fun `prerequisite - task IN_PROGRESS succeeds when blockers completed`() = runBlocking {
        val taskId = UUID.randomUUID()
        val blockerId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val blockingDep = Dependency(
            id = UUID.randomUUID(),
            fromTaskId = blockerId,
            toTaskId = taskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )

        val blockerTask = createMockTask(blockerId, "Blocker Task", TaskStatus.COMPLETED)

        coEvery { mockDependencyRepo.findByToTaskId(taskId) } returns listOf(blockingDep)
        coEvery { mockTaskRepo.getById(blockerId) } returns Result.Success(blockerTask)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(taskId, "in-progress", "task", context)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `prerequisite - task IN_PROGRESS succeeds with no dependencies`() = runBlocking {
        val taskId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        coEvery { mockDependencyRepo.findByToTaskId(taskId) } returns emptyList()

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(taskId, "in-progress", "task", context)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `prerequisite - task COMPLETED requires 300-500 char summary`() = runBlocking {
        val taskId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val taskWithShortSummary = createMockTask(taskId, "Test Task", TaskStatus.IN_PROGRESS, summary = "Too short")

        coEvery { mockTaskRepo.getById(taskId) } returns Result.Success(taskWithShortSummary)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(taskId, "completed", "task", context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("300-500 characters"))
    }

    @Test
    fun `prerequisite - task COMPLETED succeeds with valid summary length`() = runBlocking {
        val taskId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val validSummary = "A".repeat(350) // Valid length between 300-500
        val taskWithValidSummary = createMockTask(taskId, "Test Task", TaskStatus.IN_PROGRESS, summary = validSummary)

        coEvery { mockTaskRepo.getById(taskId) } returns Result.Success(taskWithValidSummary)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(taskId, "completed", "task", context)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `prerequisite - project COMPLETED requires all features completed`() = runBlocking {
        val projectId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val features = listOf(
            createMockFeature(UUID.randomUUID(), "Feature 1", FeatureStatus.COMPLETED),
            createMockFeature(UUID.randomUUID(), "Feature 2", FeatureStatus.TESTING)
        )

        coEvery { mockFeatureRepo.findByProject(projectId, 1000) } returns Result.Success(features)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(projectId, "completed", "project", context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("not completed"))
        assertTrue(invalid.reason.contains("Feature 2"))
    }

    @Test
    fun `prerequisite - project COMPLETED succeeds when all features completed`() = runBlocking {
        val projectId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val features = listOf(
            createMockFeature(UUID.randomUUID(), "Feature 1", FeatureStatus.COMPLETED),
            createMockFeature(UUID.randomUUID(), "Feature 2", FeatureStatus.COMPLETED)
        )

        coEvery { mockFeatureRepo.findByProject(projectId, 1000) } returns Result.Success(features)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(projectId, "completed", "project", context)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `prerequisite - validation can be disabled via config`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithDisabledPrerequisites(tempDir)

        val taskId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val taskWithShortSummary = createMockTask(taskId, "Test Task", TaskStatus.IN_PROGRESS, summary = "Short")

        coEvery { mockTaskRepo.getById(taskId) } returns Result.Success(taskWithShortSummary)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        // Should pass even with short summary because validation is disabled
        val result = validator.validatePrerequisites(taskId, "completed", "task", context)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `prerequisite - feature without tasks cannot transition to TESTING`() = runBlocking {
        val featureId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        coEvery { mockTaskRepo.findByFeature(featureId, null, null, 1000) } returns Result.Success(emptyList())

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(featureId, "testing", "feature", context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("must have tasks"))
    }

    @Test
    fun `prerequisite - project without features cannot transition to COMPLETED`() = runBlocking {
        val projectId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        coEvery { mockFeatureRepo.findByProject(projectId, 1000) } returns Result.Success(emptyList())

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        val result = validator.validatePrerequisites(projectId, "completed", "project", context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("must have features"))
    }

    // ========== EDGE CASE TESTS (13 tests) ==========

    @Test
    fun `edge case - corrupt config file falls back to v1 mode`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createCorruptConfig(tempDir)

        // Should fall back to enum validation
        val result = validator.validateStatus("in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `edge case - missing status_progression section returns empty`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithoutStatusProgression(tempDir)

        val statuses = validator.getAllowedStatuses("task")
        // With config present but no status_progression, returns empty (not v1 fallback)
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `edge case - missing container type section returns empty`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithMissingTasksSection(tempDir)

        val statuses = validator.getAllowedStatuses("task")
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `edge case - status with special characters is rejected`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("in@progress#", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
    }

    @Test
    fun `edge case - null-like string values are rejected`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result1 = validator.validateStatus("null", "task")
        assertTrue(result1 is StatusValidator.ValidationResult.Invalid)

        val result2 = validator.validateStatus("undefined", "task")
        assertTrue(result2 is StatusValidator.ValidationResult.Invalid)
    }

    @Test
    fun `edge case - very long status string is rejected`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val longStatus = "a".repeat(1000)
        val result = validator.validateStatus(longStatus, "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
    }

    @Test
    fun `edge case - transition to same status is idempotent`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("in-progress", "in-progress", "task")
        // Should be valid (idempotent operation)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `edge case - config cache works correctly`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        // First call loads config
        val result1 = validator.validateStatus("validating", "feature")
        assertTrue(result1 is StatusValidator.ValidationResult.Valid)

        // Second call uses cached config (within timeout)
        val result2 = validator.validateStatus("validating", "feature")
        assertTrue(result2 is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `edge case - config cache invalidates on user_dir change`(@TempDir tempDir1: Path, @TempDir tempDir2: Path) {
        // Setup first directory with config
        System.setProperty("user.dir", tempDir1.toString())
        createTestConfig(tempDir1)

        val result1 = validator.validateStatus("validating", "feature")
        assertTrue(result1 is StatusValidator.ValidationResult.Valid)

        // Change to different directory without config
        System.setProperty("user.dir", tempDir2.toString())

        // Should fall back to v1 mode (cache invalidated)
        val result2 = validator.validateStatus("validating", "feature")
        assertTrue(result2 is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `edge case - whitespace in status strings is handled`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result1 = validator.validateStatus(" in-progress ", "task")
        // Whitespace not trimmed, should be invalid
        assertTrue(result1 is StatusValidator.ValidationResult.Invalid)
    }

    @Test
    fun `edge case - invalid target status fails validation early`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateTransition("in-progress", "invalid-status", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("Invalid status"))
    }

    @Test
    fun `edge case - unknown container type returns empty allowed statuses`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val statuses = validator.getAllowedStatuses("unknown-container")
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `edge case - emergency transitions work from any non-terminal status`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        // Test from various statuses to blocked
        val fromStatuses = listOf("pending", "in-progress", "testing")
        fromStatuses.forEach { fromStatus ->
            val result = validator.validateTransition(fromStatus, "blocked", "task")
            assertTrue(result is StatusValidator.ValidationResult.Valid,
                "Expected emergency transition from $fromStatus to blocked to be valid")
        }
    }

    // ========== DEPLOYMENT TAG ADVISORY TESTS (6 tests) ==========

    @Test
    fun `deployment tag - deployed status with production tag returns valid`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "task", listOf("production", "backend"))
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "Expected Valid but got: ${if (result is StatusValidator.ValidationResult.ValidWithAdvisory) result.advisory else "other"}")
    }

    @Test
    fun `deployment tag - deployed status with staging tag returns valid`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "task", listOf("staging", "api"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `deployment tag - deployed status with canary tag returns valid`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "feature", listOf("canary", "experimental"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `deployment tag - deployed status with dev tag returns valid`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "task", listOf("dev", "testing"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `deployment tag - deployed status without environment tag returns advisory`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "task", listOf("backend", "api"))
        assertTrue(result is StatusValidator.ValidationResult.ValidWithAdvisory,
            "Expected ValidWithAdvisory but got: $result")
        val advisory = result as StatusValidator.ValidationResult.ValidWithAdvisory
        assertTrue(advisory.advisory.contains("environment tag"))
        assertTrue(advisory.advisory.contains("staging") || advisory.advisory.contains("production"))
    }

    @Test
    fun `deployment tag - deployed status with empty tags returns advisory`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "feature", emptyList())
        assertTrue(result is StatusValidator.ValidationResult.ValidWithAdvisory)
        val advisory = result as StatusValidator.ValidationResult.ValidWithAdvisory
        assertTrue(advisory.advisory.contains("environment tag"))
    }

    @Test
    fun `deployment tag - non-deployed status ignores tags`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        // Non-deployed status should not trigger advisory even without environment tags
        val result = validator.validateStatus("in-progress", "task", listOf("backend"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `deployment tag - case insensitive environment tag matching`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result1 = validator.validateStatus("deployed", "task", listOf("Production"))
        assertTrue(result1 is StatusValidator.ValidationResult.Valid)

        val result2 = validator.validateStatus("deployed", "task", listOf("STAGING"))
        assertTrue(result2 is StatusValidator.ValidationResult.Valid)

        val result3 = validator.validateStatus("deployed", "task", listOf("Prod"))
        assertTrue(result3 is StatusValidator.ValidationResult.Valid)
    }

    // ========== NEW V2.0 STATUS TESTS (14 tests) ==========

    @Test
    fun `v2 status - validates new task status TESTING`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("testing", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "TESTING should be valid task status")
    }

    @Test
    fun `v2 status - validates new task status READY_FOR_QA`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("ready-for-qa", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "READY_FOR_QA should be valid task status")
    }

    @Test
    fun `v2 status - validates new task status INVESTIGATING`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("investigating", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "INVESTIGATING should be valid task status")
    }

    @Test
    fun `v2 status - validates new task status BLOCKED`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("blocked", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "BLOCKED should be valid task status")
    }

    @Test
    fun `v2 status - validates new task status DEPLOYED`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "task", listOf("production"))
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "DEPLOYED should be valid task status")
    }

    @Test
    fun `v2 status - validates new feature status TESTING`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("testing", "feature")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "TESTING should be valid feature status")
    }

    @Test
    fun `v2 status - validates new feature status VALIDATING`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("validating", "feature")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "VALIDATING should be valid feature status")
    }

    @Test
    fun `v2 status - validates new feature status PENDING_REVIEW`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("pending-review", "feature")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "PENDING_REVIEW should be valid feature status")
    }

    @Test
    fun `v2 status - validates new feature status BLOCKED`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("blocked", "feature")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "BLOCKED should be valid feature status")
    }

    @Test
    fun `v2 status - validates new feature status DEPLOYED`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "feature", listOf("staging"))
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "DEPLOYED should be valid feature status")
    }

    @Test
    fun `v2 status - validates new project status ON_HOLD`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("on-hold", "project")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "ON_HOLD should be valid project status")
    }

    @Test
    fun `v2 status - validates new project status CANCELLED`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("cancelled", "project")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "CANCELLED should be valid project status")
    }

    @Test
    fun `v2 status - all new task statuses in getAllowedStatuses`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val statuses = validator.getAllowedStatuses("task")

        // Verify all v2.0 new statuses are present
        assertTrue(statuses.contains("testing"), "Should contain TESTING")
        assertTrue(statuses.contains("ready-for-qa"), "Should contain READY_FOR_QA")
        assertTrue(statuses.contains("investigating"), "Should contain INVESTIGATING")
        assertTrue(statuses.contains("blocked"), "Should contain BLOCKED")
        assertTrue(statuses.contains("deployed"), "Should contain DEPLOYED")
    }

    @Test
    fun `v2 status - all new feature statuses in getAllowedStatuses`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val statuses = validator.getAllowedStatuses("feature")

        // Verify all v2.0 new statuses are present
        assertTrue(statuses.contains("testing"), "Should contain TESTING")
        assertTrue(statuses.contains("validating"), "Should contain VALIDATING")
        assertTrue(statuses.contains("pending-review"), "Should contain PENDING_REVIEW")
        assertTrue(statuses.contains("blocked"), "Should contain BLOCKED")
        assertTrue(statuses.contains("deployed"), "Should contain DEPLOYED")
    }

    // ========== V2.0 DEPLOYMENT ADVISORY TESTS (8 tests) ==========

    @Test
    fun `deployment advisory - deployed task without env tag triggers advisory`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "task", listOf("backend", "api"))
        assertTrue(result is StatusValidator.ValidationResult.ValidWithAdvisory,
            "Deployed status without env tag should return ValidWithAdvisory")

        val advisory = result as StatusValidator.ValidationResult.ValidWithAdvisory
        assertTrue(advisory.advisory.contains("environment tag"))
    }

    @Test
    fun `deployment advisory - deployed feature without env tag triggers advisory`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "feature", listOf("ui", "frontend"))
        assertTrue(result is StatusValidator.ValidationResult.ValidWithAdvisory,
            "Deployed feature without env tag should return ValidWithAdvisory")

        val advisory = result as StatusValidator.ValidationResult.ValidWithAdvisory
        assertTrue(advisory.advisory.contains("environment tag"))
    }

    @Test
    fun `deployment advisory - all recognized environment tags`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val environmentTags = listOf("staging", "production", "canary", "dev", "development", "prod")

        environmentTags.forEach { envTag ->
            val result = validator.validateStatus("deployed", "task", listOf(envTag))
            assertTrue(result is StatusValidator.ValidationResult.Valid,
                "Environment tag '$envTag' should suppress advisory")
        }
    }

    @Test
    fun `deployment advisory - validateTransition preserves advisory`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateTransition("completed", "deployed", "task", tags = listOf("backend"))
        assertTrue(result is StatusValidator.ValidationResult.ValidWithAdvisory,
            "Transition to deployed without env tag should preserve advisory")

        val advisory = result as StatusValidator.ValidationResult.ValidWithAdvisory
        assertTrue(advisory.advisory.contains("environment tag"))
    }

    @Test
    fun `deployment advisory - validateTransition with env tag returns valid`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateTransition("completed", "deployed", "task", tags = listOf("production"))
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "Transition to deployed with env tag should be Valid")
    }

    @Test
    fun `deployment advisory - multiple tags with one env tag is valid`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "task", listOf("backend", "api", "staging", "feature-x"))
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "Should be Valid if at least one env tag present")
    }

    @Test
    fun `deployment advisory - advisory message suggests environment tags`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val result = validator.validateStatus("deployed", "task", emptyList())
        assertTrue(result is StatusValidator.ValidationResult.ValidWithAdvisory)

        val advisory = (result as StatusValidator.ValidationResult.ValidWithAdvisory).advisory
        // Check that advisory suggests common environment tags
        assertTrue(advisory.contains("staging") || advisory.contains("production") || advisory.contains("environment"))
    }

    @Test
    fun `deployment advisory - non-deployed status never triggers advisory`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val nonDeployedStatuses = listOf("pending", "in-progress", "testing", "completed", "blocked")

        nonDeployedStatuses.forEach { status ->
            val result = validator.validateStatus(status, "task", emptyList())
            assertFalse(result is StatusValidator.ValidationResult.ValidWithAdvisory,
                "Status '$status' should never trigger deployment advisory")
        }
    }

    // ========== V2.0 CONFIG MODE COMPREHENSIVE TESTS (12 tests) ==========

    @Test
    fun `v2 config mode - validates all task statuses from config`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val configStatuses = listOf("pending", "in-progress", "testing", "blocked", "completed", "cancelled", "deferred")

        configStatuses.forEach { status ->
            val result = validator.validateStatus(status, "task")
            assertTrue(result is StatusValidator.ValidationResult.Valid || result is StatusValidator.ValidationResult.ValidWithAdvisory,
                "Status '$status' should be valid in v2 config mode")
        }
    }

    @Test
    fun `v2 config mode - validates all feature statuses from config`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val configStatuses = listOf("planning", "in-development", "testing", "validating", "pending-review", "blocked", "completed", "archived")

        configStatuses.forEach { status ->
            val result = validator.validateStatus(status, "feature")
            assertTrue(result is StatusValidator.ValidationResult.Valid || result is StatusValidator.ValidationResult.ValidWithAdvisory,
                "Feature status '$status' should be valid in v2 config mode")
        }
    }

    @Test
    fun `v2 config mode - validates all project statuses from config`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val configStatuses = listOf("planning", "in-development", "completed", "archived", "on-hold", "cancelled")

        configStatuses.forEach { status ->
            val result = validator.validateStatus(status, "project")
            assertTrue(result is StatusValidator.ValidationResult.Valid,
                "Project status '$status' should be valid in v2 config mode")
        }
    }

    @Test
    fun `v2 config mode - terminal status blocks all transitions`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        // Task terminal statuses: completed, cancelled, deferred
        val taskTerminalStatuses = listOf("completed", "cancelled", "deferred")

        taskTerminalStatuses.forEach { terminalStatus ->
            val result = validator.validateTransition(terminalStatus, "in-progress", "task")
            assertTrue(result is StatusValidator.ValidationResult.Invalid,
                "Transition from terminal status '$terminalStatus' should be blocked")

            val invalid = result as StatusValidator.ValidationResult.Invalid
            assertTrue(invalid.reason.contains("terminal"),
                "Error message should mention 'terminal' for status '$terminalStatus'")
        }
    }

    @Test
    fun `v2 config mode - feature terminal status blocks transitions`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        // Feature terminal statuses: completed, archived
        val result = validator.validateTransition("archived", "planning", "feature")
        assertTrue(result is StatusValidator.ValidationResult.Invalid,
            "Transition from archived should be blocked")

        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("terminal"))
    }

    @Test
    fun `v2 config mode - project terminal status blocks transitions`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        // Project terminal statuses: completed, archived, cancelled
        val result = validator.validateTransition("cancelled", "in-development", "project")
        assertTrue(result is StatusValidator.ValidationResult.Invalid,
            "Transition from cancelled project should be blocked")
    }

    @Test
    fun `v2 config mode - emergency transition to blocked from any status`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val normalStatuses = listOf("pending", "in-progress", "testing")

        normalStatuses.forEach { fromStatus ->
            val result = validator.validateTransition(fromStatus, "blocked", "task")
            assertTrue(result is StatusValidator.ValidationResult.Valid,
                "Emergency transition from '$fromStatus' to blocked should be allowed")
        }
    }

    @Test
    fun `v2 config mode - emergency transition to cancelled from any status`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("testing", "cancelled", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "Emergency transition to cancelled should be allowed")
    }

    @Test
    fun `v2 config mode - emergency transition to archived for features`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("in-development", "archived", "feature")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "Emergency transition to archived should be allowed for features")
    }

    @Test
    fun `v2 config mode - backward transition allowed when enabled`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir) // Config has allow_backward: true

        val result = validator.validateTransition("testing", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "Backward transition should be allowed when allow_backward is true")
    }

    @Test
    fun `v2 config mode - config with backward disabled blocks backward transitions`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithNoBackward(tempDir)

        val result = validator.validateTransition("testing", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid,
            "Backward transition should be blocked when allow_backward is false")

        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("Backward transition"))
    }

    @Test
    fun `v2 config mode - sequential enforcement prevents status skipping`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir) // Config has enforce_sequential: true

        // Try to skip from pending directly to testing (should go through in-progress first)
        val result = validator.validateTransition("pending", "testing", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid,
            "Should not allow skipping statuses when enforce_sequential is true")

        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("skip") || invalid.reason.contains("through"))
        assertTrue(invalid.suggestions.contains("in-progress"),
            "Should suggest the next status in sequence")
    }

    // ========== V1/V2 MODE BACKWARD COMPATIBILITY TESTS (6 tests) ==========

    @Test
    fun `backward compat - v1 mode accepts all enum statuses`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        // No config file = v1 mode

        // Test all v1.0 + v2.0 task statuses are accepted
        val allTaskStatuses = listOf(
            "pending", "in-progress", "completed", "cancelled", "deferred",
            "backlog", "in-review", "changes-requested", "on-hold",
            "testing", "ready-for-qa", "investigating", "blocked", "deployed"
        )

        allTaskStatuses.forEach { status ->
            val result = validator.validateStatus(status, "task")
            assertTrue(result is StatusValidator.ValidationResult.Valid || result is StatusValidator.ValidationResult.ValidWithAdvisory,
                "v1 mode should accept enum status '$status'")
        }
    }

    @Test
    fun `backward compat - v1 mode no transition validation`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        // No config = v1 mode

        // Even weird transitions should be allowed in v1 mode (no config-based rules)
        val result = validator.validateTransition("completed", "pending", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid,
            "v1 mode should allow all transitions (no config rules)")
    }

    @Test
    fun `backward compat - v2 mode applies strict rules`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        // Same transition should be blocked in v2 mode
        val result = validator.validateTransition("completed", "pending", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid,
            "v2 mode should block transitions from terminal statuses")
    }

    @Test
    fun `backward compat - switching from v2 to v1 mode works`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        // First call in v2 mode
        val v2Result = validator.validateStatus("validating", "feature")
        assertTrue(v2Result is StatusValidator.ValidationResult.Valid)

        // Delete config to switch to v1 mode
        val configPath = tempDir.resolve(".taskorchestrator/config.yaml")
        Files.delete(configPath)

        // Force cache invalidation by changing user.dir
        val tempDir2 = Files.createTempDirectory("test2")
        System.setProperty("user.dir", tempDir2.toString())

        // Now in v1 mode - should still accept enum values
        val v1Result = validator.validateStatus("validating", "feature")
        assertTrue(v1Result is StatusValidator.ValidationResult.Valid,
            "Should still accept enum values in v1 mode")
    }

    @Test
    fun `backward compat - v1 mode getAllowedStatuses returns all enums`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        val taskStatuses = validator.getAllowedStatuses("task")

        // Should have all enum values (14 total for tasks)
        assertTrue(taskStatuses.size >= 14,
            "v1 mode should return all enum values (got ${taskStatuses.size})")
        assertTrue(taskStatuses.contains("deployed"))
        assertTrue(taskStatuses.contains("ready-for-qa"))
        assertTrue(taskStatuses.contains("investigating"))
    }

    @Test
    fun `backward compat - v2 mode getAllowedStatuses returns config values only`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithLimitedStatuses(tempDir)

        val taskStatuses = validator.getAllowedStatuses("task")

        // Should only have config-specified statuses
        assertTrue(taskStatuses.size == 3,
            "v2 mode should return only config statuses (got ${taskStatuses.size})")
        assertTrue(taskStatuses.contains("pending"))
        assertTrue(taskStatuses.contains("in-progress"))
        assertTrue(taskStatuses.contains("completed"))
        assertFalse(taskStatuses.contains("deployed"),
            "Should not contain statuses not in config")
    }

    // ========== HELPER METHODS ==========

    // ========== TAG-AWARE FLOW TESTS (Phase 2) ==========

    @Test
    fun `tag-aware flow - bug tag uses bug_fix_flow`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Bug fix flow: [pending, in-progress, testing, completed]
        // Should allow pending → in-progress
        val result = validator.validateTransition("pending", "in-progress", "task", tags = listOf("bug", "backend"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - documentation tag uses documentation_flow`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Documentation flow: [pending, in-progress, in-review, completed] (no testing)
        // Should allow in-progress → in-review
        val result = validator.validateTransition("in-progress", "in-review", "task", tags = listOf("docs"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - hotfix tag uses hotfix_flow`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Hotfix flow: [in-progress, testing, completed] (skip backlog+pending)
        // Should allow in-progress → testing
        val result = validator.validateTransition("in-progress", "testing", "task", tags = listOf("hotfix", "emergency"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - no matching tags uses default_flow`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Default flow: [backlog, pending, in-progress, testing, completed]
        // Should use default flow for unmatched tags
        val result = validator.validateTransition("backlog", "pending", "task", tags = listOf("feature", "backend"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - empty tags uses default_flow`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Default flow should be used when no tags provided
        val result = validator.validateTransition("pending", "in-progress", "task", tags = emptyList())
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - first match wins in priority order`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // If task has both "bug" and "docs" tags, bug_fix_flow should win (appears first in mappings)
        // Bug fix flow: [pending, in-progress, testing, completed]
        // Docs flow: [pending, in-progress, in-review, completed]
        // Transition to testing should be valid (in bug flow)
        val result = validator.validateTransition("in-progress", "testing", "task", tags = listOf("bug", "docs"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - case insensitive tag matching`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Tag "BUG" should match mapping tag "bug"
        val result = validator.validateTransition("pending", "in-progress", "task", tags = listOf("BUG"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - feature rapid_prototype_flow skips planning`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Rapid prototype flow: [draft, in-development, completed]
        // Should allow draft → in-development directly
        val result = validator.validateTransition("draft", "in-development", "feature", tags = listOf("prototype"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - feature experimental_flow allows archived without completion`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Experimental flow: [draft, in-development, archived]
        // Should allow in-development → archived (experiments can be archived without completion)
        val result = validator.validateTransition("in-development", "archived", "feature", tags = listOf("experiment"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - validates status not in active flow but in another flow`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // If task uses bug_fix_flow [pending, in-progress, testing, completed]
        // Transition to "in-review" (not in bug flow) should still be allowed (might be emergency/manual override)
        val result = validator.validateTransition("in-progress", "in-review", "task", tags = listOf("bug"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `tag-aware flow - blocks sequential skipping in active flow`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Bug fix flow: [pending, in-progress, testing, completed]
        // Should block pending → completed (skip in-progress and testing)
        val result = validator.validateTransition("pending", "completed", "task", tags = listOf("bug"))
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("skip"))
    }

    @Test
    fun `tag-aware flow - allows backward transitions in active flow when enabled`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithFlowMappings(tempDir)

        // Documentation flow: [pending, in-progress, in-review, completed]
        // Should allow in-review → in-progress (backward for rework)
        val result = validator.validateTransition("in-review", "in-progress", "task", tags = listOf("docs"))
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    /**
     * Creates a test configuration file in the specified directory
     */
    private fun createTestConfig(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_progression:
  features:
    default_flow: [planning, in-development, testing, validating, pending-review, completed]

    emergency_transitions: [blocked, archived]

    terminal_statuses: [completed, archived]

  tasks:
    default_flow: [pending, in-progress, testing, completed]

    emergency_transitions: [blocked, cancelled, deferred]

    terminal_statuses: [completed, cancelled, deferred]

  projects:
    default_flow: [planning, in-development, completed, archived]

    emergency_transitions: [on-hold, cancelled]

    terminal_statuses: [completed, archived, cancelled]

status_validation:
  enforce_sequential: true
  allow_backward: true
  allow_emergency: true
  validate_prerequisites: true
"""

        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }

    private fun createConfigWithCustomStatus(tempDir: Path, customStatus: String) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_progression:
  tasks:
    default_flow: [pending, in-progress, $customStatus, completed]
"""
        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }

    private fun createConfigWithLimitedStatuses(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_progression:
  tasks:
    default_flow: [pending, in-progress, completed]
"""
        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }

    private fun createConfigWithEmptyAllowedStatuses(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_progression:
  tasks:
    default_flow: [pending, in-progress, completed]
"""
        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }

    private fun createConfigWithDisabledPrerequisites(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_progression:
  tasks:
    default_flow: [pending, in-progress, completed]

status_validation:
  validate_prerequisites: false
"""
        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }

    private fun createCorruptConfig(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
this is not valid yaml: [
  broken syntax
  - incomplete
"""
        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }

    private fun createConfigWithoutStatusProgression(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_validation:
  enforce_sequential: true
"""
        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }

    private fun createConfigWithMissingTasksSection(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_progression:
  projects:
    allowed_statuses:
      - planning
      - completed

  features:
    allowed_statuses:
      - planning
      - completed
"""
        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }

    private fun createConfigWithNoBackward(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_progression:
  tasks:
    default_flow: [pending, in-progress, testing, completed]

    terminal_statuses: [completed]

status_validation:
  enforce_sequential: true
  allow_backward: false
  allow_emergency: true
"""
        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }

    private fun createMockTask(
        id: UUID,
        title: String,
        status: TaskStatus,
        summary: String = ""
    ): Task {
        return Task(
            id = id,
            title = title,
            description = "",
            summary = summary,
            status = status,
            priority = Priority.MEDIUM,
            complexity = 5,
            featureId = UUID.randomUUID(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = emptyList()
        )
    }

    private fun createMockFeature(
        id: UUID,
        name: String,
        status: FeatureStatus
    ): Feature {
        return Feature(
            id = id,
            name = name,
            description = null, // null instead of empty to avoid validation error
            summary = "",
            status = status,
            priority = Priority.MEDIUM,
            projectId = UUID.randomUUID(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = emptyList()
        )
    }

    /**
     * Creates a test config with flow_mappings for tag-aware flow testing
     */
    private fun createConfigWithFlowMappings(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_progression:
  features:
    default_flow: [draft, planning, in-development, testing, validating, completed]
    rapid_prototype_flow: [draft, in-development, completed]
    experimental_flow: [draft, in-development, archived]

    flow_mappings:
      - tags: [prototype, poc, spike]
        flow: rapid_prototype_flow
      - tags: [experiment, research]
        flow: experimental_flow

    emergency_transitions: [blocked, on-hold, archived]
    terminal_statuses: [completed, archived]

  tasks:
    default_flow: [backlog, pending, in-progress, testing, completed]
    bug_fix_flow: [pending, in-progress, testing, completed]
    documentation_flow: [pending, in-progress, in-review, completed]
    hotfix_flow: [in-progress, testing, completed]

    flow_mappings:
      - tags: [bug, bugfix, fix]
        flow: bug_fix_flow
      - tags: [documentation, docs]
        flow: documentation_flow
      - tags: [hotfix, emergency, urgent]
        flow: hotfix_flow

    emergency_transitions: [blocked, on-hold, cancelled, deferred]
    terminal_statuses: [completed, cancelled, deferred]

  projects:
    default_flow: [planning, in-development, completed, archived]
    emergency_transitions: [on-hold, cancelled]
    terminal_statuses: [completed, archived, cancelled]

status_validation:
  enforce_sequential: true
  allow_backward: true
  allow_emergency: true
  validate_prerequisites: true
"""

        Files.writeString(taskOrchestratorDir.resolve("config.yaml"), configContent)
    }
}
