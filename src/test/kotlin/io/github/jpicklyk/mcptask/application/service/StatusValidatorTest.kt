package io.github.jpicklyk.mcptask.application.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
    fun `v1 mode - transitions always allowed`(@TempDir tempDir: Path) {
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
    fun `v2 mode - allows forward transition in default flow`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("pending", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - allows backward transition when enabled`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("testing", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - blocks skipping statuses when sequential enforced`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("pending", "testing", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        val invalidResult = result as StatusValidator.ValidationResult.Invalid
        assertTrue(invalidResult.reason.contains("skip"))
        assertTrue(invalidResult.suggestions.contains("in-progress"))
    }

    @Test
    fun `v2 mode - allows emergency transitions to blocked`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("in-progress", "blocked", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - blocks transition from terminal status`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("completed", "in-progress", "task")
        assertTrue(result is StatusValidator.ValidationResult.Invalid)
        assertTrue((result as StatusValidator.ValidationResult.Invalid).reason.contains("terminal"))
    }

    @Test
    fun `v2 mode - allows emergency transition to cancelled`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("in-progress", "cancelled", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - allows emergency transition to deferred`(@TempDir tempDir: Path) {
        System.setProperty("user.dir", tempDir.toString())
        createTestConfig(tempDir)

        val result = validator.validateTransition("testing", "deferred", "task")
        assertTrue(result is StatusValidator.ValidationResult.Valid)
    }

    @Test
    fun `v2 mode - feature transitions work correctly`(@TempDir tempDir: Path) {
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
    fun `v2 mode - project transitions work correctly`(@TempDir tempDir: Path) {
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
}
