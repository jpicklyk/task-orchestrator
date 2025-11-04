package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Tests for updated validateTransition() signature with containerId and context parameters.
 *
 * Tests verify that:
 * 1. validateTransition() can be called without containerId/context (backward compatibility)
 * 2. validateTransition() performs prerequisite validation when containerId/context provided
 * 3. ManageContainerTool integration works correctly
 */
class ValidateTransitionSignatureTest {

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

    // ========== BACKWARD COMPATIBILITY TESTS ==========

    @Test
    fun `backward compatibility - validateTransition works without containerId and context`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        // Should work like the old signature - no prerequisite validation
        val result = validator.validateTransition("pending", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `backward compatibility - validateTransition validates basic transition rules`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        // Should still validate transition rules (terminal status blocking)
        val result = validator.validateTransition("completed", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("terminal"))
    }

    @Test
    fun `backward compatibility - validateTransition validates status is valid`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        // Should still validate that the target status is valid
        val result = validator.validateTransition("pending", "invalid-status", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("Invalid status"))
    }

    // ========== PREREQUISITE VALIDATION INTEGRATION TESTS ==========

    @Test
    fun `prerequisite integration - task COMPLETED validates summary when context provided`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

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

        // Should fail prerequisite validation
        val result = validator.validateTransition("in-progress", "completed", "task", taskId, context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("300-500 characters"))
    }

    @Test
    fun `prerequisite integration - task COMPLETED succeeds with valid summary`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        val taskId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val validSummary = "A".repeat(350) // Valid length
        val taskWithValidSummary = createMockTask(taskId, "Test Task", TaskStatus.IN_PROGRESS, summary = validSummary)
        coEvery { mockTaskRepo.getById(taskId) } returns Result.Success(taskWithValidSummary)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        // Should pass both transition and prerequisite validation
        val result = validator.validateTransition("in-progress", "completed", "task", taskId, context)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `prerequisite integration - task IN_PROGRESS blocked by incomplete dependencies`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
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

        // Should fail prerequisite validation
        val result = validator.validateTransition("pending", "in-progress", "task", taskId, context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("blocked by"))
    }

    @Test
    fun `prerequisite integration - feature IN_DEVELOPMENT requires at least 1 task`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        val featureId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        coEvery { mockFeatureRepo.getTaskCount(featureId) } returns Result.Success(0)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        // Should fail prerequisite validation
        val result = validator.validateTransition("planning", "in-development", "feature", featureId, context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("at least 1 task"))
    }

    @Test
    fun `prerequisite integration - feature TESTING requires all tasks completed`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

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

        // Should fail prerequisite validation
        val result = validator.validateTransition("in-development", "testing", "feature", featureId, context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("not completed"))
    }

    @Test
    fun `prerequisite integration - project COMPLETED requires all features completed`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

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

        // Should fail prerequisite validation
        val result = validator.validateTransition("in-development", "completed", "project", projectId, context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("not completed"))
    }

    // ========== COMBINED VALIDATION TESTS ==========

    @Test
    fun `combined validation - transition rule fails before prerequisite check`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val taskId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        // Mock would fail prerequisite check, but transition rule should fail first
        val taskWithShortSummary = createMockTask(taskId, "Test Task", TaskStatus.COMPLETED, summary = "Short")
        coEvery { mockTaskRepo.getById(taskId) } returns Result.Success(taskWithShortSummary)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        // Should fail on terminal status transition rule, not prerequisite
        val result = validator.validateTransition("completed", "in-progress", "task", taskId, context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("terminal"))
        assertFalse(invalid.reason.contains("summary")) // Should NOT reach prerequisite check
    }

    @Test
    fun `combined validation - invalid status fails before prerequisite check`() = runBlocking {
        val taskId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        // Should fail on invalid status, not prerequisite
        val result = validator.validateTransition("pending", "invalid-status", "task", taskId, context)
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalid = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("Invalid status"))
    }

    @Test
    fun `combined validation - all validations pass when conditions met`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        val taskId = UUID.randomUUID()
        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val validSummary = "A".repeat(350)
        val taskWithValidSummary = createMockTask(taskId, "Test Task", TaskStatus.IN_PROGRESS, summary = validSummary)
        coEvery { mockTaskRepo.getById(taskId) } returns Result.Success(taskWithValidSummary)

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        // Should pass: valid status, valid transition, valid prerequisite
        val result = validator.validateTransition("in-progress", "completed", "task", taskId, context)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    // ========== OPTIONAL PARAMETER TESTS ==========

    @Test
    fun `optional params - containerId without context skips prerequisite validation`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        val taskId = UUID.randomUUID()

        // Should not throw - prerequisite check skipped if context is null
        val result = validator.validateTransition("in-progress", "completed", "task", taskId, null)
        // Basic validation passes (no prerequisite check)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `optional params - context without containerId skips prerequisite validation`(@TempDir tempDir: Path) = runBlocking {
        System.setProperty("user.dir", tempDir.toString())

        val mockFeatureRepo = mockk<FeatureRepository>()
        val mockTaskRepo = mockk<TaskRepository>()
        val mockProjectRepo = mockk<ProjectRepository>()
        val mockDependencyRepo = mockk<DependencyRepository>()

        val context = StatusValidator.PrerequisiteContext(
            mockTaskRepo, mockFeatureRepo, mockProjectRepo, mockDependencyRepo
        )

        // Should not throw - prerequisite check skipped if containerId is null
        val result = validator.validateTransition("in-progress", "completed", "task", null, context)
        // Basic validation passes (no prerequisite check)
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    // ========== HELPER METHODS ==========

    private fun createTestConfig(tempDir: Path) {
        val taskOrchestratorDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(taskOrchestratorDir)

        val configContent = """
version: "2.0.0"

status_progression:
  tasks:
    allowed_statuses:
      - pending
      - in-progress
      - testing
      - completed
      - cancelled

    default_flow:
      - pending
      - in-progress
      - testing
      - completed

    terminal_statuses:
      - completed
      - cancelled

status_validation:
  enforce_sequential: true
  allow_backward: true
  allow_emergency: true
  validate_prerequisites: true
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
            description = null,
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
