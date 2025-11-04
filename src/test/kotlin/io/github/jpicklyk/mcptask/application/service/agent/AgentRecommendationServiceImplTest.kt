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

          - task_tags: [bug, error, complex, performance, optimization, refactor]
            agent: senior-engineer
            section_tags: [bug-report, reproduction, investigation, technical-approach]

        tagPriority:
          - bug
          - error
          - complex
          - performance
          - optimization
          - refactor
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
        fun `should prioritize complex tag over backend tag`() {
            // Arrange
            val task = Task(
                title = "Refactor auth architecture",
                summary = "Complex refactoring of authentication system",
                tags = listOf("complex", "refactor", "architecture", "backend")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation)
            assertEquals("senior-engineer", recommendation.agentName,
                "Should prioritize complex over backend based on tagPriority")
            assertTrue(recommendation.reason.contains("priority category: complex"))
        }

        @Test
        fun `should prioritize bug tag over backend tag`() {
            // Arrange
            val task = Task(
                title = "Fix NullPointerException in UserService",
                summary = "Backend service throwing NPE",
                tags = listOf("bug", "error", "backend")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation)
            assertEquals("senior-engineer", recommendation.agentName,
                "Should prioritize bug over backend based on tagPriority")
            assertTrue(recommendation.reason.contains("priority category: bug"))
        }

        @Test
        fun `should prioritize performance tag over backend tag`() {
            // Arrange
            val task = Task(
                title = "Optimize database query performance",
                summary = "Backend API endpoints running slowly",
                tags = listOf("performance", "optimization", "backend")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation)
            assertEquals("senior-engineer", recommendation.agentName,
                "Should prioritize performance over backend based on tagPriority")
            assertTrue(recommendation.reason.contains("priority category: performance"))
        }

        @Test
        fun `should prioritize refactor tag over backend tag`() {
            // Arrange
            val task = Task(
                title = "Refactor legacy code",
                summary = "Clean up technical debt in backend services",
                tags = listOf("refactor", "backend", "service")
            )
            every { agentDirectoryManager.readAgentMappingFile() } returns validAgentMappingYaml

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation)
            assertEquals("senior-engineer", recommendation.agentName,
                "Should prioritize refactor over backend based on tagPriority")
            assertTrue(recommendation.reason.contains("priority category: refactor"))
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
    inner class FallbackBehavior {

        @Test
        fun `should use default specialist when no tags match and fallback is use_default`() {
            // Arrange
            val task = Task(
                title = "Generic task",
                summary = "Some work",
                tags = listOf("unknown-tag", "no-match")
            )
            val yamlWithFallback = """
                tagMappings:
                  - task_tags: [backend, api, service]
                    agent: Backend Engineer
                    section_tags: [requirements, technical-approach, implementation]

                  - task_tags: [frontend, ui, react]
                    agent: Frontend Developer
                    section_tags: [requirements, design]

                tagPriority:
                  - backend
                  - frontend

                default_specialist: Backend Engineer
                fallback_behavior: use_default
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns yamlWithFallback

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should return default specialist recommendation")
            assertEquals("Backend Engineer", recommendation.agentName)
            assertEquals("No matching tags found. Using default specialist from configuration.", recommendation.reason)
            assertTrue(recommendation.matchedTags.isEmpty(), "Should have no matched tags")
            assertEquals(3, recommendation.sectionTags.size, "Should include section tags from default specialist")
            assertTrue(recommendation.sectionTags.contains("requirements"))
            assertTrue(recommendation.sectionTags.contains("technical-approach"))
            assertTrue(recommendation.sectionTags.contains("implementation"))
        }

        @Test
        fun `should return null when fallback_behavior is skip`() {
            // Arrange
            val task = Task(
                title = "Generic task",
                summary = "Some work",
                tags = listOf("unknown-tag")
            )
            val yamlWithSkip = """
                tagMappings:
                  - task_tags: [backend, api]
                    agent: Backend Engineer
                    section_tags: [requirements]

                default_specialist: Backend Engineer
                fallback_behavior: skip
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns yamlWithSkip

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should return null when fallback_behavior is skip")
        }

        @Test
        fun `should return null when default_specialist is null`() {
            // Arrange
            val task = Task(
                title = "Generic task",
                summary = "Some work",
                tags = listOf("unknown-tag")
            )
            val yamlWithNullDefault = """
                tagMappings:
                  - task_tags: [backend, api]
                    agent: Backend Engineer
                    section_tags: [requirements]

                default_specialist: null
                fallback_behavior: use_default
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns yamlWithNullDefault

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should return null when default_specialist is null")
        }

        @Test
        fun `should return null when default_specialist is missing`() {
            // Arrange
            val task = Task(
                title = "Generic task",
                summary = "Some work",
                tags = listOf("unknown-tag")
            )
            val yamlWithoutDefault = """
                tagMappings:
                  - task_tags: [backend, api]
                    agent: Backend Engineer
                    section_tags: [requirements]

                fallback_behavior: use_default
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns yamlWithoutDefault

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should return null when default_specialist is not specified")
        }

        @Test
        fun `should handle default specialist not in tagMappings`() {
            // Arrange
            val task = Task(
                title = "Generic task",
                summary = "Some work",
                tags = listOf("unknown-tag")
            )
            val yamlWithUnmappedDefault = """
                tagMappings:
                  - task_tags: [backend, api]
                    agent: Backend Engineer
                    section_tags: [requirements]

                default_specialist: Test Engineer
                fallback_behavior: use_default
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns yamlWithUnmappedDefault

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should still return recommendation")
            assertEquals("Test Engineer", recommendation.agentName)
            assertTrue(recommendation.sectionTags.isEmpty(), "Should have empty section tags when specialist not in mappings")
            assertEquals("No matching tags found. Using default specialist from configuration.", recommendation.reason)
        }

        @Test
        fun `should default to skip when fallback_behavior is missing`() {
            // Arrange
            val task = Task(
                title = "Generic task",
                summary = "Some work",
                tags = listOf("unknown-tag")
            )
            val yamlWithoutFallbackBehavior = """
                tagMappings:
                  - task_tags: [backend, api]
                    agent: Backend Engineer
                    section_tags: [requirements]

                default_specialist: Backend Engineer
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns yamlWithoutFallbackBehavior

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should default to skip behavior when fallback_behavior is missing")
        }

        @Test
        fun `should handle invalid fallback_behavior value`() {
            // Arrange
            val task = Task(
                title = "Generic task",
                summary = "Some work",
                tags = listOf("unknown-tag")
            )
            val yamlWithInvalidFallback = """
                tagMappings:
                  - task_tags: [backend, api]
                    agent: Backend Engineer
                    section_tags: [requirements]

                default_specialist: Backend Engineer
                fallback_behavior: invalid_value
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns yamlWithInvalidFallback

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should treat invalid fallback_behavior as skip")
        }

        @Test
        fun `should not use fallback when tags match`() {
            // Arrange
            val task = Task(
                title = "Backend task",
                summary = "API work",
                tags = listOf("backend", "api")
            )
            val yamlWithFallback = """
                tagMappings:
                  - task_tags: [backend, api]
                    agent: Backend Engineer
                    section_tags: [requirements]

                  - task_tags: [frontend]
                    agent: Frontend Developer
                    section_tags: [design]

                default_specialist: Frontend Developer
                fallback_behavior: use_default
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns yamlWithFallback

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should find matching agent")
            assertEquals("Backend Engineer", recommendation.agentName, "Should use matched agent, not default")
            assertTrue(recommendation.matchedTags.isNotEmpty(), "Should have matched tags")
            assertTrue(recommendation.reason.contains("match"), "Reason should indicate tag matching")
        }

        @Test
        fun `should handle empty string default_specialist`() {
            // Arrange
            val task = Task(
                title = "Generic task",
                summary = "Some work",
                tags = listOf("unknown-tag")
            )
            val yamlWithEmptyDefault = """
                tagMappings:
                  - task_tags: [backend, api]
                    agent: Backend Engineer
                    section_tags: [requirements]

                default_specialist: ""
                fallback_behavior: use_default
            """.trimIndent()
            every { agentDirectoryManager.readAgentMappingFile() } returns yamlWithEmptyDefault

            // Act
            val recommendation = service.recommendAgent(task)

            // Assert
            assertNull(recommendation, "Should return null when default_specialist is empty string")
        }
    }

    @Nested
    inner class ActualConfigFileIntegration {

        @BeforeEach
        fun setup() {
            // Set up the agent configuration for tests
            val agentDirectoryManager = AgentDirectoryManager()
            agentDirectoryManager.createDirectoryStructure()
            agentDirectoryManager.copyDefaultAgentMapping()
        }

        @Test
        fun `should successfully load and parse actual agent-mapping yaml file`() {
            // Arrange - Use real AgentDirectoryManager (no mocking)
            val realAgentDirectoryManager = AgentDirectoryManager()
            val realService = AgentRecommendationServiceImpl(realAgentDirectoryManager)

            // Act - Try to list agents (this loads the config)
            val agents = realService.listAvailableAgents()

            // Assert
            assertTrue(agents.isNotEmpty(), "Should load agents from real config file")
            // v2.0 architecture agents
            assertTrue(agents.contains("Implementation Specialist"), "Should contain Implementation Specialist")
            assertTrue(agents.contains("Senior Engineer"), "Should contain Senior Engineer")
            assertTrue(agents.contains("Feature Architect"), "Should contain Feature Architect")
            assertTrue(agents.contains("Planning Specialist"), "Should contain Planning Specialist")
        }

        @Test
        fun `should load default_specialist from actual config file`() {
            // Arrange - Use real AgentDirectoryManager
            val realAgentDirectoryManager = AgentDirectoryManager()
            val realService = AgentRecommendationServiceImpl(realAgentDirectoryManager)

            // Create a task with no matching tags
            val task = Task(
                title = "Generic task with no matching tags",
                summary = "This task has tags that don't match any mappings",
                tags = listOf("unknown-tag-xyz", "no-match-abc")
            )

            // Act
            val recommendation = realService.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should return recommendation using default_specialist from config")
            assertEquals("implementation-specialist", recommendation.agentName,
                "default_specialist should be 'implementation-specialist' as configured in agent-mapping.yaml")
            assertEquals("No matching tags found. Using default specialist from configuration.",
                recommendation.reason)
        }

        @Test
        fun `should load fallback_behavior from actual config file`() {
            // Arrange - Use real AgentDirectoryManager
            val realAgentDirectoryManager = AgentDirectoryManager()
            val realService = AgentRecommendationServiceImpl(realAgentDirectoryManager)

            // Create a task with no matching tags
            val task = Task(
                title = "Generic task",
                summary = "Task with no matches",
                tags = listOf("unmatched-tag")
            )

            // Act
            val recommendation = realService.recommendAgent(task)

            // Assert - Config should have fallback_behavior: use_default
            assertNotNull(recommendation,
                "Should return recommendation because fallback_behavior is 'use_default' in config")
        }

        @Test
        fun `should load tag mappings from actual config file`() {
            // Arrange - Use real AgentDirectoryManager
            val realAgentDirectoryManager = AgentDirectoryManager()
            val realService = AgentRecommendationServiceImpl(realAgentDirectoryManager)

            // Test backend tag mapping - v2.0 routes to Implementation Specialist
            val backendTask = Task(
                title = "Backend task",
                summary = "API work",
                tags = listOf("backend", "api")
            )

            // Act
            val backendRecommendation = realService.recommendAgent(backendTask)

            // Assert
            assertNotNull(backendRecommendation, "Should match backend tags from config")
            assertEquals("Implementation Specialist", backendRecommendation.agentName)
            assertTrue(backendRecommendation.sectionTags.contains("requirements"),
                "Should include section tags from config")
            assertTrue(backendRecommendation.sectionTags.contains("technical-approach"),
                "Should include section tags from config")
        }

        @Test
        fun `should load tag priority from actual config file`() {
            // Arrange - Use real AgentDirectoryManager
            val realAgentDirectoryManager = AgentDirectoryManager()
            val realService = AgentRecommendationServiceImpl(realAgentDirectoryManager)

            // Create task with both database and backend tags
            // According to tagPriority in config, database should take precedence
            val task = Task(
                title = "Database migration with backend changes",
                summary = "Schema and API updates",
                tags = listOf("backend", "database", "migration")
            )

            // Act
            val recommendation = realService.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should match tags from config")
            // v2.0: Database is still priority, but routes to Implementation Specialist
            assertEquals("Implementation Specialist", recommendation.agentName,
                "Should prioritize database over backend based on tagPriority in config")
            assertTrue(recommendation.reason.contains("priority category: database"),
                "Reason should indicate priority-based selection")
        }

        @Test
        fun `should load complex tag priority from actual config file`() {
            // Arrange - Use real AgentDirectoryManager
            val realAgentDirectoryManager = AgentDirectoryManager()
            val realService = AgentRecommendationServiceImpl(realAgentDirectoryManager)

            // Create task with complex and backend tags
            // According to v2.0 tagPriority, complex should take precedence → Senior Engineer
            val task = Task(
                title = "Refactor auth architecture",
                summary = "Complex refactoring of authentication system",
                tags = listOf("complex", "refactor", "architecture", "backend")
            )

            // Act
            val recommendation = realService.recommendAgent(task)

            // Assert
            assertNotNull(recommendation, "Should match tags from config")
            assertEquals("Senior Engineer", recommendation.agentName,
                "Should prioritize complex over backend based on tagPriority in config")
            assertTrue(recommendation.reason.contains("priority category: complex"),
                "Reason should indicate priority-based selection")
        }

        @Test
        fun `actual config file should have valid structure`() {
            // Arrange - Use real AgentDirectoryManager
            val realAgentDirectoryManager = AgentDirectoryManager()
            val realService = AgentRecommendationServiceImpl(realAgentDirectoryManager)

            // Create test tasks for various scenarios
            val tasks = listOf(
                Task(title = "Frontend task", summary = "UI work", tags = listOf("frontend")),
                Task(title = "Database task", summary = "Schema work", tags = listOf("database")),
                Task(title = "Testing task", summary = "QA work", tags = listOf("testing")),
                Task(title = "Documentation task", summary = "Docs work", tags = listOf("documentation")),
                Task(title = "Bug fix", summary = "Fix issue", tags = listOf("bug"))
            )

            // Act & Assert - All should get recommendations
            tasks.forEach { task ->
                val recommendation = realService.recommendAgent(task)
                assertNotNull(recommendation,
                    "Config should have mapping for ${task.tags.first()} tag")
                assertNotNull(recommendation.agentName,
                    "Agent name should not be null for ${task.tags.first()}")
                assertTrue(recommendation.agentName.isNotBlank(),
                    "Agent name should not be blank for ${task.tags.first()}")
            }
        }

        @Test
        fun `actual config should have section tags for all mapped agents`() {
            // Arrange - Use real AgentDirectoryManager
            val realAgentDirectoryManager = AgentDirectoryManager()
            val realService = AgentRecommendationServiceImpl(realAgentDirectoryManager)

            // Get all agents from config
            val agents = realService.listAvailableAgents()

            // Act & Assert - Check section tags for v2.0 agents
            val mainAgents = listOf(
                "Implementation Specialist",
                "Senior Engineer",
                "Feature Architect",
                "Planning Specialist"
            )

            mainAgents.forEach { agentName ->
                val sectionTags = realService.getSectionTagsForAgent(agentName)
                assertTrue(sectionTags.isNotEmpty(),
                    "$agentName should have section tags defined in config")
            }
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
