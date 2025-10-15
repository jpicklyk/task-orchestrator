package io.github.jpicklyk.mcptask.application.service.agent

import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.infrastructure.filesystem.AgentDirectoryManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for AgentRecommendationServiceImpl
 *
 * Tests cover: tag matching (single/multiple/priority), workflow phase matching,
 * fallback behavior, missing config file handling, malformed YAML handling, edge cases
 */
class AgentRecommendationServiceImplTest {

    private lateinit var agentDirectoryManager: AgentDirectoryManager
    private lateinit var service: AgentRecommendationServiceImpl

    private val validAgentMappingYaml = """
        workflowPhases:
          planning: planning-specialist
          documentation: technical-writer

        tagMappings:
          - task_tags: [backend, api, service]
            agent: backend-engineer
            section_tags: [requirements, technical-approach, implementation]

          - task_tags: [frontend, ui, react]
            agent: frontend-developer
            section_tags: [requirements, technical-approach, design]

          - task_tags: [testing, test, qa]
            agent: test-engineer
            section_tags: [requirements, testing-strategy, acceptance-criteria]

          - task_tags: [database, migration, schema]
            agent: database-engineer
            section_tags: [requirements, technical-approach, data-model]

        tagPriority:
          - database
          - backend
          - frontend
          - testing

        entityTypes:
          FEATURE:
            planning: planning-specialist
          TASK:
            fallback: null
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        agentDirectoryManager = mockk<AgentDirectoryManager>()
        service = AgentRecommendationServiceImpl(agentDirectoryManager)
    }

    @Nested
    inner class RecommendAgent {

        @Test
        fun `should return backend-engineer when task has backend tag`() {
            // Arrange
            val task = Task(
                title = "Implement REST API",
                summary = "Create API endpoints",
                tags = listOf("backend", "api")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should return a recommendation")
            assertEquals("backend-engineer", recommendation.agentName)
            assertTrue(recommendation.matchedTags.contains("backend"))
            assertTrue(recommendation.sectionTags.contains("requirements"))
            assertTrue(recommendation.sectionTags.contains("technical-approach"))
            assertTrue(recommendation.sectionTags.contains("implementation"))
        }

        @Test
        fun `should return test-engineer when task has testing tag`() {
            // Arrange
            val task = Task(
                title = "Add unit tests",
                summary = "Write comprehensive tests",
                tags = listOf("testing", "unit-tests")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation)
            assertEquals("test-engineer", recommendation.agentName)
            assertTrue(recommendation.matchedTags.contains("testing"))
            assertTrue(recommendation.sectionTags.contains("testing-strategy"))
        }

        @Test
        fun `should prioritize database tag over backend tag`() {
            // Arrange
            val task = Task(
                title = "Create database migration",
                summary = "Add new table and API endpoint",
                tags = listOf("backend", "database", "migration")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation)
            assertEquals("database-engineer", recommendation.agentName,
                "Should prioritize database over backend based on tagPriority")
            assertTrue(recommendation.reason.contains("priority category: database"))
        }

        @Test
        fun `should match multiple tags and return most relevant agent`() {
            // Arrange
            val task = Task(
                title = "Build React UI",
                summary = "Create frontend components",
                tags = listOf("frontend", "react", "ui", "components")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation)
            assertEquals("frontend-developer", recommendation.agentName)
            assertTrue(recommendation.matchedTags.size >= 2, "Should match multiple tags")
            assertTrue(recommendation.matchedTags.containsAll(listOf("frontend", "react", "ui")))
        }

        @Test
        fun `should return null when no tags match`() {
            // Arrange
            val task = Task(
                title = "Documentation task",
                summary = "Write user guide",
                tags = listOf("documentation", "user-guide")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should return null when no tags match agent mappings")
        }

        @Test
        fun `should return null when task has empty tags`() {
            // Arrange
            val task = Task(
                title = "Generic task",
                summary = "Some work"
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should return null when task has no tags")
        }

        @Test
        fun `should handle case-insensitive tag matching`() {
            // Arrange
            val task = Task(
                title = "Backend task",
                summary = "API work",
                tags = listOf("BACKEND", "API")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should match tags case-insensitively")
            assertEquals("backend-engineer", recommendation.agentName)
        }

        @Test
        fun `should return null when config file is missing`() {
            // Arrange
            val task = Task(
                title = "Some task",
                summary = "Work to do",
                tags = listOf("backend")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns null

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should return null when config file is missing")
        }

        @Test
        fun `should return null when YAML is malformed`() {
            // Arrange
            val task = Task(
                title = "Backend task",
                summary = "API work",
                tags = listOf("backend")
            )
            val malformedYaml = "this is: not: valid: yaml: [[[{"
            every { agentDirectoryManager.readAgentMappingFile() } returns malformedYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should gracefully handle malformed YAML")
        }

        @Test
        fun `should handle tags with whitespace`() {
            // Arrange
            val task = Task(
                title = "Backend task",
                summary = "API work",
                tags = listOf("  backend  ", " api ")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should trim whitespace from tags")
            assertEquals("backend-engineer", recommendation.agentName)
        }
    }

    @Nested
    inner class ListAvailableAgents {

        @Test
        fun `should return sorted list of agents from config`() {
            // Arrange
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val agents = service.listAvailableAgents()

            // Assert
            assertTrue(agents.isNotEmpty(), "Should return list of agents")
            assertTrue(agents.contains("backend-engineer"))
            assertTrue(agents.contains("frontend-developer"))
            assertTrue(agents.contains("test-engineer"))
            assertTrue(agents.contains("database-engineer"))
            assertTrue(agents.contains("planning-specialist"))
            assertEquals(agents, agents.sorted(), "Agent list should be sorted")
        }

        @Test
        fun `should return unique agent names from config`() {
            // Arrange
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val agents = service.listAvailableAgents()

            // Assert
            assertEquals(agents.size, agents.distinct().size, "Should not have duplicate agents")
        }

        @Test
        fun `should fallback to listing agent files when config is missing`() {
            // Arrange
            every { agentDirectoryManager.readAgentMappingFile() } returns null
            every { agentDirectoryManager.listAgentFiles() } returns listOf(
                "backend-engineer.md",
                "frontend-developer.md",
                "test-engineer.md"
            )

            // Act
            val agents = service.listAvailableAgents()

            // Assert
            assertTrue(agents.isNotEmpty(), "Should fallback to file listing")
            assertEquals(3, agents.size)
            assertTrue(agents.contains("backend-engineer"))
            assertTrue(agents.contains("frontend-developer"))
            assertTrue(agents.contains("test-engineer"))
            verify { agentDirectoryManager.listAgentFiles() }
        }

        @Test
        fun `should handle empty agent file list when config is missing`() {
            // Arrange
            every { agentDirectoryManager.readAgentMappingFile() } returns null
            every { agentDirectoryManager.listAgentFiles() } returns emptyList()

            // Act
            val agents = service.listAvailableAgents()

            // Assert
            assertTrue(agents.isEmpty(), "Should return empty list when no agents found")
        }
    }

    @Nested
    inner class GetSectionTagsForAgent {

        @Test
        fun `should return section tags for backend-engineer`() {
            // Arrange
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val sectionTags = service.getSectionTagsForAgent("backend-engineer")

            // Assert
            assertEquals(3, sectionTags.size)
            assertTrue(sectionTags.contains("requirements"))
            assertTrue(sectionTags.contains("technical-approach"))
            assertTrue(sectionTags.contains("implementation"))
        }

        @Test
        fun `should return section tags for test-engineer`() {
            // Arrange
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val sectionTags = service.getSectionTagsForAgent("test-engineer")

            // Assert
            assertEquals(3, sectionTags.size)
            assertTrue(sectionTags.contains("requirements"))
            assertTrue(sectionTags.contains("testing-strategy"))
            assertTrue(sectionTags.contains("acceptance-criteria"))
        }

        @Test
        fun `should return empty list for unknown agent`() {
            // Arrange
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val sectionTags = service.getSectionTagsForAgent("nonexistent-agent")

            // Assert
            assertTrue(sectionTags.isEmpty(), "Should return empty list for unknown agent")
        }

        @Test
        fun `should return empty list when config is missing`() {
            // Arrange
            every { agentDirectoryManager.readAgentMappingFile() } returns null

            // Act
            val sectionTags = service.getSectionTagsForAgent("backend-engineer")

            // Assert
            assertTrue(sectionTags.isEmpty(), "Should return empty list when config is missing")
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should handle config with missing sections`() {
            // Arrange
            val task = Task(
                title = "Task",
                summary = "Work",
                tags = listOf("backend")
            )
            val minimalYaml = """
                tagMappings:
                  - task_tags: [backend]
                    agent: backend-engineer
                    section_tags: [requirements]
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns minimalYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should handle minimal config")
            assertEquals("backend-engineer", recommendation.agentName)
        }

        @Test
        fun `should handle config with empty tag mappings`() {
            // Arrange
            val task = Task(
                title = "Task",
                summary = "Work",
                tags = listOf("backend")
            )
            val emptyMappingsYaml = """
                tagMappings: []
                tagPriority: []
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns emptyMappingsYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should return null when tagMappings is empty")
        }

        @Test
        fun `should handle task with UUID correctly`() {
            // Arrange
            val taskId = UUID.randomUUID()
            val task = Task(
                id = taskId,
                title = "Backend task",
                summary = "API work",
                status = TaskStatus.PENDING,
                priority = Priority.MEDIUM,
                complexity = 5,
                tags = listOf("backend", "api")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should handle real Task instance with UUID")
            assertEquals("backend-engineer", recommendation.agentName)
        }
    }
}
