package io.github.jpicklyk.mcptask.application.service.progression

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for StatusProgressionServiceImpl
 *
 * Tests cover:
 * - Config loading with caching (60-second timeout)
 * - Flow determination based on tags with priority ordering
 * - Next status calculation in workflow sequences
 * - Terminal status detection
 * - Readiness checking with StatusValidator integration
 * - Emergency transition handling
 * - Edge cases (missing config, empty flows, invalid statuses)
 */
class StatusProgressionServiceImplTest {

    private lateinit var statusValidator: StatusValidator
    private lateinit var service: StatusProgressionServiceImpl

    @TempDir
    lateinit var tempDir: Path

    private val validConfigYaml = """
        status_progression:
          tasks:
            default_flow:
              - backlog
              - pending
              - in-progress
              - testing
              - completed

            bug_fix_flow:
              - reported
              - triaged
              - in-progress
              - testing
              - verified
              - closed

            documentation_flow:
              - draft
              - review
              - approved
              - published

            terminal_statuses:
              - completed
              - cancelled
              - deferred
              - closed

            emergency_transitions:
              - blocked
              - cancelled
              - archived

            flow_mappings:
              - tags: [bug, fix, hotfix]
                flow: bug_fix_flow
              - tags: [documentation, docs]
                flow: documentation_flow

          features:
            default_flow:
              - planning
              - in-development
              - testing
              - completed

            terminal_statuses:
              - completed
              - cancelled

            emergency_transitions:
              - blocked
              - archived

          projects:
            default_flow:
              - planning
              - active
              - completed

            terminal_statuses:
              - completed
              - archived

            emergency_transitions:
              - archived
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        statusValidator = mockk<StatusValidator>()
        service = StatusProgressionServiceImpl(statusValidator)

        // Set user.dir to temp directory for testing
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
    }

    private fun createConfigFile(content: String = validConfigYaml) {
        val configDir = tempDir.resolve(".taskorchestrator").toFile()
        configDir.mkdirs()
        val configFile = configDir.resolve("config.yaml")
        configFile.writeText(content)
    }

    @Nested
    inner class GetNextStatus {

        @Test
        fun `should return next status in default flow`() = runBlocking {
            // Arrange
            createConfigFile()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "pending",
                    newStatus = "in-progress",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = emptyList()
                )
            } returns StatusValidator.ValidationResult.Valid

            // Act
            val result = service.getNextStatus(
                currentStatus = "pending",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("in-progress", result.recommendedStatus)
            assertEquals("default_flow", result.activeFlow)
            assertEquals(listOf("backlog", "pending", "in-progress", "testing", "completed"), result.flowSequence)
            assertEquals(1, result.currentPosition) // "pending" is at index 1
            assertTrue(result.matchedTags.isEmpty())
            assertTrue(result.reason.contains("Next status"))
        }

        @Test
        fun `should use bug_fix_flow when task has bug tag`() = runBlocking {
            // Arrange
            createConfigFile()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "reported",
                    newStatus = "triaged",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = listOf("bug", "backend")
                )
            } returns StatusValidator.ValidationResult.Valid

            // Act
            val result = service.getNextStatus(
                currentStatus = "reported",
                containerType = "task",
                tags = listOf("bug", "backend"),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("triaged", result.recommendedStatus)
            assertEquals("bug_fix_flow", result.activeFlow)
            assertEquals(listOf("reported", "triaged", "in-progress", "testing", "verified", "closed"), result.flowSequence)
            assertEquals(0, result.currentPosition) // "reported" is at index 0
            assertTrue(result.matchedTags.contains("bug"))
        }

        @Test
        fun `should use documentation_flow when task has docs tag`() = runBlocking {
            // Arrange
            createConfigFile()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "draft",
                    newStatus = "review",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = listOf("documentation")
                )
            } returns StatusValidator.ValidationResult.Valid

            // Act
            val result = service.getNextStatus(
                currentStatus = "draft",
                containerType = "task",
                tags = listOf("documentation"),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("review", result.recommendedStatus)
            assertEquals("documentation_flow", result.activeFlow)
            assertEquals(listOf("draft", "review", "approved", "published"), result.flowSequence)
            assertEquals(0, result.currentPosition)
            assertTrue(result.matchedTags.contains("documentation"))
        }

        @Test
        fun `should return Terminal when at terminal status`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "completed",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Terminal>(result)
            assertEquals("completed", result.terminalStatus)
            assertEquals("default_flow", result.activeFlow)
            assertTrue(result.reason.contains("terminal"))
        }

        @Test
        fun `should return Terminal when at end of flow`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "completed",
                containerType = "task",
                tags = listOf("bug"),
                containerId = null
            )

            // Assert - "closed" is terminal in bug_fix_flow
            assertIs<NextStatusRecommendation.Terminal>(result)
        }

        @Test
        fun `should return Blocked when transition validation fails`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID() // Need ID for validation to run
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "pending",
                    newStatus = "in-progress",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = emptyList()
                )
            } returns StatusValidator.ValidationResult.Invalid(
                reason = "Summary required",
                suggestions = listOf("Add summary to task")
            )

            // Act
            val result = service.getNextStatus(
                currentStatus = "pending",
                containerType = "task",
                tags = emptyList(),
                containerId = taskId // Pass ID to trigger validation
            )

            // Assert
            assertIs<NextStatusRecommendation.Blocked>(result)
            assertEquals("pending", result.currentStatus)
            assertEquals(1, result.blockers.size)
            assertTrue(result.blockers[0].contains("Summary required"))
            assertEquals("default_flow", result.activeFlow)
            assertEquals(1, result.currentPosition)
        }

        @Test
        fun `should return Terminal when status not found in flow`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "invalid-status",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Terminal>(result)
            assertTrue(result.reason.contains("not found in workflow"))
        }

        @Test
        fun `should normalize status for comparison`() = runBlocking {
            // Arrange
            createConfigFile()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "IN_PROGRESS",
                    newStatus = "testing",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = emptyList()
                )
            } returns StatusValidator.ValidationResult.Valid

            // Act - uppercase with underscores should match "in-progress"
            val result = service.getNextStatus(
                currentStatus = "IN_PROGRESS",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("testing", result.recommendedStatus)
            assertEquals(2, result.currentPosition) // "in-progress" is at index 2
        }

        @Test
        fun `should handle missing config gracefully`() = runBlocking {
            // Arrange - no config file created

            // Act
            val result = service.getNextStatus(
                currentStatus = "pending",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Terminal>(result)
            assertTrue(result.reason.contains("Configuration not found"))
        }

        @Test
        fun `should work with features`() = runBlocking {
            // Arrange
            createConfigFile()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "planning",
                    newStatus = "in-development",
                    containerType = "feature",
                    containerId = null,
                    context = null,
                    tags = emptyList()
                )
            } returns StatusValidator.ValidationResult.Valid

            // Act
            val result = service.getNextStatus(
                currentStatus = "planning",
                containerType = "feature",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("in-development", result.recommendedStatus)
            assertEquals("default_flow", result.activeFlow)
            assertEquals(listOf("planning", "in-development", "testing", "completed"), result.flowSequence)
        }

        @Test
        fun `should work with projects`() = runBlocking {
            // Arrange
            createConfigFile()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "planning",
                    newStatus = "active",
                    containerType = "project",
                    containerId = null,
                    context = null,
                    tags = emptyList()
                )
            } returns StatusValidator.ValidationResult.Valid

            // Act
            val result = service.getNextStatus(
                currentStatus = "planning",
                containerType = "project",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("active", result.recommendedStatus)
            assertEquals("default_flow", result.activeFlow)
        }
    }

    @Nested
    inner class GetFlowPath {

        @Test
        fun `should return complete flow path for default flow`() {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getFlowPath(
                containerType = "task",
                tags = emptyList(),
                currentStatus = "pending"
            )

            // Assert
            assertEquals("default_flow", result.activeFlow)
            assertEquals(listOf("backlog", "pending", "in-progress", "testing", "completed"), result.flowSequence)
            assertEquals(1, result.currentPosition) // "pending" is at index 1
            assertTrue(result.matchedTags.isEmpty())
            assertEquals(listOf("completed", "cancelled", "deferred", "closed"), result.terminalStatuses)
            assertEquals(listOf("blocked", "cancelled", "archived"), result.emergencyTransitions)
        }

        @Test
        fun `should return bug_fix_flow path when bug tag present`() {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getFlowPath(
                containerType = "task",
                tags = listOf("bug"),
                currentStatus = "triaged"
            )

            // Assert
            assertEquals("bug_fix_flow", result.activeFlow)
            assertEquals(listOf("reported", "triaged", "in-progress", "testing", "verified", "closed"), result.flowSequence)
            assertEquals(1, result.currentPosition)
            assertTrue(result.matchedTags.contains("bug"))
        }

        @Test
        fun `should handle null currentStatus`() {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getFlowPath(
                containerType = "task",
                tags = emptyList(),
                currentStatus = null
            )

            // Assert
            assertEquals("default_flow", result.activeFlow)
            assertNull(result.currentPosition)
            assertEquals(listOf("backlog", "pending", "in-progress", "testing", "completed"), result.flowSequence)
        }

        @Test
        fun `should handle status not in flow`() {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getFlowPath(
                containerType = "task",
                tags = emptyList(),
                currentStatus = "invalid-status"
            )

            // Assert
            assertEquals("default_flow", result.activeFlow)
            assertNull(result.currentPosition) // Status not found, so null
        }

        @Test
        fun `should handle missing config`() {
            // Arrange - no config file

            // Act
            val result = service.getFlowPath(
                containerType = "task",
                tags = emptyList(),
                currentStatus = "pending"
            )

            // Assert
            assertEquals("unknown", result.activeFlow)
            assertTrue(result.flowSequence.isEmpty())
            assertNull(result.currentPosition)
            assertTrue(result.terminalStatuses.isEmpty())
            assertTrue(result.emergencyTransitions.isEmpty())
        }

        @Test
        fun `should work with features`() {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getFlowPath(
                containerType = "feature",
                tags = emptyList(),
                currentStatus = "planning"
            )

            // Assert
            assertEquals("default_flow", result.activeFlow)
            assertEquals(listOf("planning", "in-development", "testing", "completed"), result.flowSequence)
            assertEquals(0, result.currentPosition)
            assertEquals(listOf("completed", "cancelled"), result.terminalStatuses)
            assertEquals(listOf("blocked", "archived"), result.emergencyTransitions)
        }
    }

    @Nested
    inner class CheckReadiness {

        @Test
        fun `should return Ready when transition is valid`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "pending",
                    newStatus = "in-progress",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = emptyList()
                )
            } returns StatusValidator.ValidationResult.Valid

            // Act
            val result = service.checkReadiness(
                currentStatus = "pending",
                targetStatus = "in-progress",
                containerType = "task",
                tags = emptyList(),
                containerId = taskId
            )

            // Assert
            assertIs<ReadinessResult.Ready>(result)
            assertTrue(result.isValid)
            assertTrue(result.reason.contains("valid"))
        }

        @Test
        fun `should return Ready with advisory when transition has advisory`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "pending",
                    newStatus = "in-progress",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = emptyList()
                )
            } returns StatusValidator.ValidationResult.ValidWithAdvisory("Consider adding environment tag")

            // Act
            val result = service.checkReadiness(
                currentStatus = "pending",
                targetStatus = "in-progress",
                containerType = "task",
                tags = emptyList(),
                containerId = taskId
            )

            // Assert
            assertIs<ReadinessResult.Ready>(result)
            assertTrue(result.reason.contains("Advisory"))
        }

        @Test
        fun `should return NotReady when validation fails`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "pending",
                    newStatus = "completed",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = emptyList()
                )
            } returns StatusValidator.ValidationResult.Invalid(
                reason = "Summary required",
                suggestions = listOf("Add 300-500 char summary")
            )

            // Act
            val result = service.checkReadiness(
                currentStatus = "pending",
                targetStatus = "completed",
                containerType = "task",
                tags = emptyList(),
                containerId = taskId
            )

            // Assert
            assertIs<ReadinessResult.NotReady>(result)
            assertEquals(1, result.blockers.size)
            assertTrue(result.blockers[0].contains("Summary required"))
            assertEquals(1, result.suggestions.size)
        }

        @Test
        fun `should return Invalid when target status not in flow`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID()

            // Act
            val result = service.checkReadiness(
                currentStatus = "pending",
                targetStatus = "invalid-status",
                containerType = "task",
                tags = emptyList(),
                containerId = taskId
            )

            // Assert
            assertIs<ReadinessResult.Invalid>(result)
            assertTrue(result.reason.contains("not valid in active workflow"))
            assertEquals(listOf("backlog", "pending", "in-progress", "testing", "completed"), result.allowedStatuses)
        }

        @Test
        fun `should return Invalid when current status is terminal`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID()

            // Act
            val result = service.checkReadiness(
                currentStatus = "completed",
                targetStatus = "in-progress",
                containerType = "task",
                tags = emptyList(),
                containerId = taskId
            )

            // Assert
            assertIs<ReadinessResult.Invalid>(result)
            assertTrue(result.reason.contains("Cannot transition from terminal status"))
            assertTrue(result.allowedStatuses.isEmpty())
        }

        @Test
        fun `should handle missing config`() = runBlocking {
            // Arrange - no config file
            val taskId = UUID.randomUUID()

            // Act
            val result = service.checkReadiness(
                currentStatus = "pending",
                targetStatus = "in-progress",
                containerType = "task",
                tags = emptyList(),
                containerId = taskId
            )

            // Assert
            assertIs<ReadinessResult.Invalid>(result)
            assertTrue(result.reason.contains("Configuration not found"))
        }
    }

    @Nested
    inner class ConfigCaching {

        @Test
        fun `should cache config and not reload within timeout`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act - Load config twice
            val result1 = service.getFlowPath("task", emptyList(), null)

            // Modify config file (should not affect result due to caching)
            val configFile = tempDir.resolve(".taskorchestrator/config.yaml").toFile()
            configFile.writeText("status_progression: {}")

            val result2 = service.getFlowPath("task", emptyList(), null)

            // Assert - Both should return same flow (cached)
            assertEquals(result1.flowSequence, result2.flowSequence)
            assertEquals(result1.activeFlow, result2.activeFlow)
        }

        @Test
        fun `should handle malformed YAML gracefully`() {
            // Arrange
            createConfigFile("invalid: yaml: content: [[[[")

            // Act
            val result = service.getFlowPath("task", emptyList(), null)

            // Assert
            assertEquals("unknown", result.activeFlow)
            assertTrue(result.flowSequence.isEmpty())
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should handle empty flow sequence`() = runBlocking {
            // Arrange
            val configWithEmptyFlow = """
                status_progression:
                  tasks:
                    default_flow: []
                    terminal_statuses: []
                    emergency_transitions: []
            """.trimIndent()
            createConfigFile(configWithEmptyFlow)

            // Act
            val result = service.getNextStatus(
                currentStatus = "pending",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Terminal>(result)
            assertTrue(result.reason.contains("empty"))
        }

        @Test
        fun `should handle case-insensitive tag matching`() = runBlocking {
            // Arrange
            createConfigFile()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "reported",
                    newStatus = "triaged",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = listOf("BUG", "BACKEND")
                )
            } returns StatusValidator.ValidationResult.Valid

            // Act - uppercase tags should match
            val result = service.getNextStatus(
                currentStatus = "reported",
                containerType = "task",
                tags = listOf("BUG", "BACKEND"),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("bug_fix_flow", result.activeFlow)
        }

        @Test
        fun `should handle first match wins for flow mappings`() = runBlocking {
            // Arrange - config has bug before docs in flow_mappings
            createConfigFile()
            coEvery {
                statusValidator.validateTransition(
                    currentStatus = "reported",
                    newStatus = "triaged",
                    containerType = "task",
                    containerId = null,
                    context = null,
                    tags = listOf("bug", "documentation") // Has both
                )
            } returns StatusValidator.ValidationResult.Valid

            // Act
            val result = service.getNextStatus(
                currentStatus = "reported",
                containerType = "task",
                tags = listOf("bug", "documentation"), // Has both tags
                containerId = null
            )

            // Assert - Should use bug_fix_flow (first match in mappings)
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("bug_fix_flow", result.activeFlow)
        }
    }
}
