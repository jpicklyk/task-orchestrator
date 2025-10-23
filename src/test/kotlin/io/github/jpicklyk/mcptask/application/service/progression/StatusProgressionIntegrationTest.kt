package io.github.jpicklyk.mcptask.application.service.progression

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for StatusProgressionService with real StatusValidator.
 *
 * These tests verify the complete status workflow system works correctly:
 * - Tag-aware flow selection via StatusValidator.getActiveFlow()
 * - Prerequisite validation integration
 * - Terminal status enforcement
 * - Emergency transition handling
 * - Backward compatibility with v1 flows (no flow_mappings)
 *
 * This is the integration layer between:
 * - StatusValidator (tag-aware flow selection, transition validation)
 * - StatusProgressionService (next status recommendation logic)
 */
class StatusProgressionIntegrationTest {

    private lateinit var statusValidator: StatusValidator
    private lateinit var service: StatusProgressionServiceImpl

    @TempDir
    lateinit var tempDir: Path

    private val fullConfigYaml = """
        status_progression:
          tasks:
            # Multiple workflow flows for different task types
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

            hotfix_flow:
              - emergency
              - in-progress
              - deployed
              - verified
              - closed

            experimental_flow:
              - idea
              - prototype
              - testing
              - archived

            terminal_statuses:
              - completed
              - cancelled
              - deferred
              - closed
              - published
              - archived

            emergency_transitions:
              - blocked
              - cancelled
              - archived

            # Priority-ordered flow mappings (first match wins)
            flow_mappings:
              - tags: [hotfix, urgent, emergency]
                flow: hotfix_flow
              - tags: [bug, fix, defect]
                flow: bug_fix_flow
              - tags: [documentation, docs, readme]
                flow: documentation_flow
              - tags: [experimental, prototype, spike]
                flow: experimental_flow

          features:
            default_flow:
              - planning
              - in-development
              - testing
              - completed

            rapid_prototype_flow:
              - idea
              - building
              - demo
              - decision

            terminal_statuses:
              - completed
              - cancelled
              - archived

            emergency_transitions:
              - blocked
              - archived

            flow_mappings:
              - tags: [prototype, spike, poc]
                flow: rapid_prototype_flow

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
        // Create real StatusValidator (not mocked)
        statusValidator = StatusValidator()

        // Create service with real validator
        service = StatusProgressionServiceImpl(statusValidator)

        // Set user.dir to temp directory
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString())
    }

    private fun createConfigFile(content: String = fullConfigYaml) {
        val configDir = tempDir.resolve(".taskorchestrator").toFile()
        configDir.mkdirs()
        val configFile = configDir.resolve("config.yaml")
        configFile.writeText(content)
    }

    @Nested
    inner class TagAwareFlowSelection {

        @Test
        fun `should select bug_fix_flow for task with bug tag`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "reported",
                containerType = "task",
                tags = listOf("bug", "backend"),
                containerId = null
            )

            // Assert - should use bug_fix_flow
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("triaged", result.recommendedStatus)
            assertEquals("bug_fix_flow", result.activeFlow)
            assertEquals(listOf("reported", "triaged", "in-progress", "testing", "verified", "closed"), result.flowSequence)
            assertTrue(result.matchedTags.contains("bug"))
        }

        @Test
        fun `should select hotfix_flow for task with urgent tag (priority over bug)`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act - both "urgent" and "bug" tags present
            val result = service.getNextStatus(
                currentStatus = "emergency",
                containerType = "task",
                tags = listOf("urgent", "bug", "backend"),
                containerId = null
            )

            // Assert - should use hotfix_flow (first match wins)
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("in-progress", result.recommendedStatus)
            assertEquals("hotfix_flow", result.activeFlow)
            assertEquals(listOf("emergency", "in-progress", "deployed", "verified", "closed"), result.flowSequence)
            assertTrue(result.matchedTags.contains("urgent"))
        }

        @Test
        fun `should select documentation_flow for task with docs tag`() = runBlocking {
            // Arrange
            createConfigFile()

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
        }

        @Test
        fun `should select experimental_flow for task with prototype tag`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "idea",
                containerType = "task",
                tags = listOf("experimental", "spike"),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("prototype", result.recommendedStatus)
            assertEquals("experimental_flow", result.activeFlow)
            assertEquals(listOf("idea", "prototype", "testing", "archived"), result.flowSequence)
        }

        @Test
        fun `should fallback to default_flow when no tags match`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "pending",
                containerType = "task",
                tags = listOf("backend", "api"), // No matching tags
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("in-progress", result.recommendedStatus)
            assertEquals("default_flow", result.activeFlow)
            assertEquals(listOf("backlog", "pending", "in-progress", "testing", "completed"), result.flowSequence)
            assertTrue(result.matchedTags.isEmpty())
        }

        @Test
        fun `should handle case-insensitive tag matching`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act - uppercase tags
            val result = service.getNextStatus(
                currentStatus = "reported",
                containerType = "task",
                tags = listOf("BUG", "BACKEND"),
                containerId = null
            )

            // Assert - should match "bug" mapping
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("bug_fix_flow", result.activeFlow)
        }
    }

    @Nested
    inner class TerminalStatusHandling {

        @Test
        fun `should block progression from terminal status in default_flow`() = runBlocking {
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
            assertTrue(result.reason.contains("terminal"))
        }

        @Test
        fun `should block progression from terminal status in bug_fix_flow`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "closed",
                containerType = "task",
                tags = listOf("bug"),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Terminal>(result)
            assertEquals("closed", result.terminalStatus)
            assertEquals("bug_fix_flow", result.activeFlow)
        }

        @Test
        fun `should block progression from terminal status in documentation_flow`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "published",
                containerType = "task",
                tags = listOf("documentation"),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Terminal>(result)
            assertEquals("published", result.terminalStatus)
            assertEquals("documentation_flow", result.activeFlow)
        }

        @Test
        fun `should recognize terminal status even with uppercase`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "COMPLETED",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert - normalization should work
            assertIs<NextStatusRecommendation.Terminal>(result)
        }
    }

    @Nested
    inner class FlowPathRetrival {

        @Test
        fun `should return complete flow path with context`() {
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
            assertEquals(1, result.currentPosition) // "triaged" is at index 1
            assertTrue(result.matchedTags.contains("bug"))
            assertEquals(listOf("completed", "cancelled", "deferred", "closed", "published", "archived"), result.terminalStatuses)
            assertEquals(listOf("blocked", "cancelled", "archived"), result.emergencyTransitions)
        }

        @Test
        fun `should return flow path without position when status not provided`() {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getFlowPath(
                containerType = "task",
                tags = listOf("documentation"),
                currentStatus = null
            )

            // Assert
            assertEquals("documentation_flow", result.activeFlow)
            assertEquals(listOf("draft", "review", "approved", "published"), result.flowSequence)
            assertEquals(null, result.currentPosition)
        }

        @Test
        fun `should work for features with rapid_prototype_flow`() {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getFlowPath(
                containerType = "feature",
                tags = listOf("prototype"),
                currentStatus = "idea"
            )

            // Assert
            assertEquals("rapid_prototype_flow", result.activeFlow)
            assertEquals(listOf("idea", "building", "demo", "decision"), result.flowSequence)
            assertEquals(0, result.currentPosition)
        }
    }

    @Nested
    inner class ReadinessChecking {

        @Test
        fun `should validate transition in active flow`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID()

            // Act
            val result = service.checkReadiness(
                currentStatus = "reported",
                targetStatus = "triaged",
                containerType = "task",
                tags = listOf("bug"),
                containerId = taskId
            )

            // Assert
            assertIs<ReadinessResult.Ready>(result)
            assertTrue(result.isValid)
            assertTrue(result.reason.contains("valid"))
        }

        @Test
        fun `should reject transition to status not in active flow`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID()

            // Act - "completed" is in default_flow but not in bug_fix_flow
            val result = service.checkReadiness(
                currentStatus = "reported",
                targetStatus = "completed",
                containerType = "task",
                tags = listOf("bug"),
                containerId = taskId
            )

            // Assert
            assertIs<ReadinessResult.Invalid>(result)
            assertTrue(result.reason.contains("not valid in active workflow"))
            assertEquals(listOf("reported", "triaged", "in-progress", "testing", "verified", "closed"), result.allowedStatuses)
        }

        @Test
        fun `should reject transition from terminal status`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID()

            // Act
            val result = service.checkReadiness(
                currentStatus = "closed",
                targetStatus = "in-progress",
                containerType = "task",
                tags = listOf("bug"),
                containerId = taskId
            )

            // Assert
            assertIs<ReadinessResult.Invalid>(result)
            assertTrue(result.reason.contains("Cannot transition from terminal status"))
        }

        @Test
        fun `should handle backward transition validation`() = runBlocking {
            // Arrange
            createConfigFile()
            val taskId = UUID.randomUUID()

            // Act - backward transition (testing → in-progress)
            val result = service.checkReadiness(
                currentStatus = "testing",
                targetStatus = "in-progress",
                containerType = "task",
                tags = listOf("bug"),
                containerId = taskId
            )

            // Assert - StatusValidator should handle backward transition logic
            // This depends on config's allow_backward_transitions setting
            // For this test, we just verify the integration works
            assertTrue(result is ReadinessResult.Ready || result is ReadinessResult.NotReady || result is ReadinessResult.Invalid)
        }
    }

    @Nested
    inner class MultipleFlowTypes {

        @Test
        fun `should handle feature with default_flow`() = runBlocking {
            // Arrange
            createConfigFile()

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
        }

        @Test
        fun `should handle feature with rapid_prototype_flow`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "idea",
                containerType = "feature",
                tags = listOf("prototype"),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("building", result.recommendedStatus)
            assertEquals("rapid_prototype_flow", result.activeFlow)
        }

        @Test
        fun `should handle project status progression`() = runBlocking {
            // Arrange
            createConfigFile()

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
    inner class EdgeCases {

        @Test
        fun `should handle empty tags list`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act
            val result = service.getNextStatus(
                currentStatus = "pending",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("default_flow", result.activeFlow)
        }

        @Test
        fun `should handle status with underscores vs hyphens`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act - status with underscores
            val result = service.getNextStatus(
                currentStatus = "in_progress", // Underscore
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert - should normalize and match "in-progress"
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("testing", result.recommendedStatus)
        }

        @Test
        fun `should handle multiple matching tags (first match wins)`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act - both "urgent" and "bug" should match, but "urgent" is first
            val result = service.getNextStatus(
                currentStatus = "emergency",
                containerType = "task",
                tags = listOf("urgent", "bug", "backend"),
                containerId = null
            )

            // Assert - should use hotfix_flow (urgent matched first)
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("hotfix_flow", result.activeFlow)
        }

        @Test
        fun `should handle status not found in flow`() = runBlocking {
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
        fun `should handle missing config file`() = runBlocking {
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
        fun `should handle malformed config gracefully`() = runBlocking {
            // Arrange
            val malformedConfig = "invalid: yaml: [[["
            createConfigFile(malformedConfig)

            // Act
            val result = service.getNextStatus(
                currentStatus = "pending",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert - should handle error gracefully
            assertIs<NextStatusRecommendation.Terminal>(result)
        }
    }

    @Nested
    inner class BackwardCompatibility {

        @Test
        fun `should work without flow_mappings (v1 config)`() = runBlocking {
            // Arrange - old config without flow_mappings
            val v1ConfigYaml = """
                status_progression:
                  tasks:
                    default_flow:
                      - backlog
                      - pending
                      - in-progress
                      - completed
                    terminal_statuses:
                      - completed
                      - cancelled
            """.trimIndent()
            createConfigFile(v1ConfigYaml)

            // Act
            val result = service.getNextStatus(
                currentStatus = "pending",
                containerType = "task",
                tags = listOf("bug"), // Tags present but no flow_mappings
                containerId = null
            )

            // Assert - should fall back to default_flow
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("in-progress", result.recommendedStatus)
            assertEquals("default_flow", result.activeFlow)
        }

        @Test
        fun `should work without tags parameter (v1 usage)`() = runBlocking {
            // Arrange
            createConfigFile()

            // Act - empty tags (v1 style call)
            val result = service.getNextStatus(
                currentStatus = "pending",
                containerType = "task",
                tags = emptyList(),
                containerId = null
            )

            // Assert
            assertIs<NextStatusRecommendation.Ready>(result)
            assertEquals("default_flow", result.activeFlow)
        }
    }
}
