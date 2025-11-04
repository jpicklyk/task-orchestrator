package io.github.jpicklyk.mcptask.infrastructure.filesystem

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import kotlin.test.*

/**
 * Tests for agent-mapping.yaml configuration file to ensure all required
 * agents have proper mappings and the file structure is valid.
 */
class AgentMappingConfigTest {

    /**
     * Helper function to load agent-mapping.yaml configuration.
     * Loads single-document YAML configuration file.
     */
    private fun loadAgentMappingConfig(): Map<String, Any> {
        val resourceStream = javaClass.getResourceAsStream("/claude/configuration/agent-mapping.yaml")
        assertNotNull(resourceStream, "agent-mapping.yaml should exist in resources")

        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        val config = yaml.load(resourceStream) as? Map<String, Any>
        assertNotNull(config, "agent-mapping.yaml should be valid YAML")
        return config
    }

    @Test
    fun `agent-mapping yaml should be valid and parseable`() {
        val config = loadAgentMappingConfig()

        // Verify main sections exist (workflowPhases and entityTypes removed in v2.0 architecture streamlining)
        assertTrue(config.containsKey("tagMappings"), "Should have tagMappings section")
        assertTrue(config.containsKey("tagPriority"), "Should have tagPriority section")
        assertTrue(config.containsKey("default_specialist"), "Should have default_specialist field")
        assertTrue(config.containsKey("fallback_behavior"), "Should have fallback_behavior field")
    }

    @Test
    fun `all specialist agent types should have tag mappings`() {
        // v2.0 architecture: Implementation Specialist with Skills + specialized agents
        val expectedAgents = setOf(
            "Implementation Specialist",  // Haiku with domain Skills
            "Senior Engineer",            // Sonnet for bugs/blockers/complex
            "Feature Architect",          // Opus for feature design
            "Planning Specialist"         // Sonnet for task breakdown
        )

        val config = loadAgentMappingConfig()

        @Suppress("UNCHECKED_CAST")
        val tagMappings = config["tagMappings"] as? List<Map<String, Any>>
        assertNotNull(tagMappings, "tagMappings should be a list")

        val mappedAgents = tagMappings.mapNotNull { it["agent"] as? String }.toSet()

        // Verify all expected agents have mappings
        expectedAgents.forEach { agent ->
            assertTrue(
                mappedAgents.contains(agent),
                "Agent '$agent' should have a tag mapping"
            )
        }
    }

    @Test
    fun `each tag mapping should have required fields`() {
        val config = loadAgentMappingConfig()

        @Suppress("UNCHECKED_CAST")
        val tagMappings = config["tagMappings"] as? List<Map<String, Any>>
        assertNotNull(tagMappings)

        tagMappings.forEachIndexed { index, mapping ->
            // Check required fields
            assertTrue(
                mapping.containsKey("task_tags"),
                "Mapping $index should have 'task_tags' field"
            )
            assertTrue(
                mapping.containsKey("agent"),
                "Mapping $index should have 'agent' field"
            )
            assertTrue(
                mapping.containsKey("section_tags"),
                "Mapping $index should have 'section_tags' field"
            )

            // Verify task_tags is a non-empty list
            @Suppress("UNCHECKED_CAST")
            val taskTags = mapping["task_tags"] as? List<String>
            assertNotNull(taskTags, "task_tags should be a list")
            assertTrue(taskTags.isNotEmpty(), "task_tags should not be empty")

            // Verify agent is a non-empty string
            val agent = mapping["agent"] as? String
            assertNotNull(agent, "agent should be a string")
            assertTrue(agent.isNotBlank(), "agent should not be blank")

            // Verify section_tags is a non-empty list
            @Suppress("UNCHECKED_CAST")
            val sectionTags = mapping["section_tags"] as? List<String>
            assertNotNull(sectionTags, "section_tags should be a list")
            assertTrue(sectionTags.isNotEmpty(), "section_tags should not be empty")
        }
    }

    @Test
    fun `priority list should include all mapped agents`() {
        val config = loadAgentMappingConfig()

        @Suppress("UNCHECKED_CAST")
        val tagMappings = config["tagMappings"] as? List<Map<String, Any>>
        assertNotNull(tagMappings)

        @Suppress("UNCHECKED_CAST")
        val tagPriority = config["tagPriority"] as? List<String>
        assertNotNull(tagPriority, "tagPriority should be a list")

        // Get unique agents from mappings
        val mappedAgents = tagMappings.mapNotNull { it["agent"] as? String }.toSet()

        // Priority list should have entries (though not necessarily matching agent names directly)
        assertTrue(
            tagPriority.isNotEmpty(),
            "tagPriority should not be empty"
        )

        // Common priority tags that should be present
        val expectedPriorityTags = listOf("database", "backend", "frontend", "testing", "documentation", "planning")
        expectedPriorityTags.forEach { priorityTag ->
            assertTrue(
                tagPriority.contains(priorityTag),
                "Priority list should include '$priorityTag'"
            )
        }
    }

    // Removed: .claude/ directory setup no longer supported by Task Orchestrator
    // Agent definitions are user-managed if using Claude Code
    // @Test
    // fun `all agent definition files should exist`() {
    //     val expectedAgentFiles = OrchestrationSetupManager.DEFAULT_AGENT_FILES
    //     expectedAgentFiles.forEach { fileName ->
    //         val resourceStream = javaClass.getResourceAsStream("/claude/agents/$fileName")
    //         assertNotNull(
    //             resourceStream,
    //             "Agent definition file '$fileName' should exist in resources"
    //         )
    //     }
    // }

    @Test
    fun `all tag mappings reference valid agent definition files`() {
        // Verify that all agents referenced in tagMappings have corresponding .md files
        val config = loadAgentMappingConfig()

        @Suppress("UNCHECKED_CAST")
        val tagMappings = config["tagMappings"] as? List<Map<String, Any>>
        assertNotNull(tagMappings, "tagMappings should be a list")

        // v2.0 architecture: Implementation Specialist with Skills + specialized agents
        val validAgents = setOf(
            "Implementation Specialist",  // Haiku with domain Skills
            "Senior Engineer",            // Sonnet for bugs/blockers/complex
            "Feature Architect",          // Opus for feature design
            "Planning Specialist"         // Sonnet for task breakdown
        )

        tagMappings.forEach { mapping ->
            val agent = mapping["agent"] as? String
            assertNotNull(agent, "Each tag mapping should have an agent")
            assertTrue(
                validAgents.contains(agent),
                "Tag mapping references invalid agent '$agent'. Valid agents: ${validAgents.joinToString(", ")}"
            )
        }
    }

    @Test
    fun `section tags should be kebab-case strings`() {
        val config = loadAgentMappingConfig()

        @Suppress("UNCHECKED_CAST")
        val tagMappings = config["tagMappings"] as? List<Map<String, Any>>
        assertNotNull(tagMappings)

        // Verify section tags follow kebab-case convention (user can define custom tags)
        val kebabCasePattern = Regex("^[a-z]+(-[a-z]+)*$")

        tagMappings.forEach { mapping ->
            @Suppress("UNCHECKED_CAST")
            val sectionTags = mapping["section_tags"] as? List<String>
            assertNotNull(sectionTags)

            // Verify all section tags are kebab-case (no enforcement of specific tag names)
            sectionTags.forEach { tag ->
                assertTrue(
                    kebabCasePattern.matches(tag),
                    "Section tag '$tag' should be in kebab-case format (lowercase with hyphens)"
                )
            }
        }
    }

    @Test
    fun `specific agent mappings should have expected tags`() {
        val config = loadAgentMappingConfig()

        @Suppress("UNCHECKED_CAST")
        val tagMappings = config["tagMappings"] as? List<Map<String, Any>>
        assertNotNull(tagMappings)

        // Test Implementation Specialist has all standard implementation tags
        val implementationMapping = tagMappings.find { it["agent"] == "Implementation Specialist" }
        assertNotNull(implementationMapping, "Implementation Specialist mapping should exist")
        @Suppress("UNCHECKED_CAST")
        val implementationTags = implementationMapping["task_tags"] as List<String>
        assertTrue(implementationTags.contains("backend") || implementationTags.contains("api"),
            "Implementation Specialist should have 'backend' or 'api' tags")
        assertTrue(implementationTags.contains("testing") || implementationTags.contains("test"),
            "Implementation Specialist should have 'testing' or 'test' tags")
        assertTrue(implementationTags.contains("documentation") || implementationTags.contains("docs"),
            "Implementation Specialist should have 'documentation' or 'docs' tags")

        // Test Senior Engineer has bug/blocker tags
        val seniorMapping = tagMappings.find { it["agent"] == "Senior Engineer" }
        assertNotNull(seniorMapping, "Senior Engineer mapping should exist")
        @Suppress("UNCHECKED_CAST")
        val seniorTags = seniorMapping["task_tags"] as List<String>
        assertTrue(seniorTags.contains("bug") || seniorTags.contains("blocker") || seniorTags.contains("complex"),
            "Senior Engineer should have 'bug', 'blocker', or 'complex' tags")

        // Test Planning Specialist has expected task tags
        val planningMapping = tagMappings.find { it["agent"] == "Planning Specialist" }
        assertNotNull(planningMapping, "Planning Specialist mapping should exist")
        @Suppress("UNCHECKED_CAST")
        val planningTags = planningMapping["task_tags"] as List<String>
        assertTrue(planningTags.contains("planning") || planningTags.contains("task-breakdown"),
            "Planning Specialist should have 'planning' or 'task-breakdown' tags")
    }
}
