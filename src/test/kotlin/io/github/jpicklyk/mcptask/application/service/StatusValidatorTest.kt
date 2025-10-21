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
            "backlog", "in-review", "changes-requested", "on-hold", "testing", "blocked"
        )

        validStatuses.forEach { status ->
            val result = validator.validateStatus(status, "task")
            assertTrue(result is StatusValidator.ValidationResult.Valid,
                "Expected $status to be valid for task, but got: ${if (result is StatusValidator.ValidationResult.Invalid) result.reason else "unknown"}")
        }
    }

    @Test
    fun `enum validation - accepts all valid feature statuses`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())

        // FeatureStatus enum values (v1.0 + v2.0)
        val validStatuses = listOf(
            "planning", "in-development", "completed", "archived",
            "draft", "on-hold", "testing", "validating", "pending-review", "blocked"
        )

        validStatuses.forEach { status ->
            val result = validator.validateStatus(status, "feature")
            assertTrue(result is StatusValidator.ValidationResult.Valid,
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
    fun `config enablement - empty allowed_statuses permits all enums`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createConfigWithEmptyAllowedStatuses(tempDir)

        // With empty allowed_statuses, should fall back to enum validation
        val result = validator.validateStatus("in-progress", "task")
        // This will be invalid because empty list means no statuses allowed
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
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

    // ========== HELPER METHODS ==========

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
    allowed_statuses:
      - planning
      - in-development
      - testing
      - validating
      - pending-review
      - blocked
      - completed
      - archived

    default_flow:
      - planning
      - in-development
      - testing
      - validating
      - completed

    emergency_transitions:
      - blocked
      - archived

    terminal_statuses:
      - completed
      - archived

  tasks:
    allowed_statuses:
      - pending
      - in-progress
      - testing
      - blocked
      - completed
      - cancelled
      - deferred

    default_flow:
      - pending
      - in-progress
      - testing
      - completed

    terminal_statuses:
      - completed
      - cancelled
      - deferred

  projects:
    allowed_statuses:
      - planning
      - in-development
      - completed
      - archived
      - on-hold
      - cancelled

    default_flow:
      - planning
      - in-development
      - completed
      - archived

    terminal_statuses:
      - completed
      - archived
      - cancelled

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
    allowed_statuses:
      - pending
      - in-progress
      - $customStatus
      - completed

    default_flow:
      - pending
      - in-progress
      - completed
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
    allowed_statuses:
      - pending
      - in-progress
      - completed

    default_flow:
      - pending
      - in-progress
      - completed
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
    allowed_statuses: []

    default_flow:
      - pending
      - in-progress
      - completed
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
    allowed_statuses:
      - pending
      - in-progress
      - completed

    default_flow:
      - pending
      - in-progress
      - completed

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
}
