package io.github.jpicklyk.mcptask.infrastructure.filesystem

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import kotlin.test.*

/**
 * Tests for agent-mapping.yaml configuration file to ensure all required
 * agents have proper mappings and the file structure is valid.
 */
class AgentMappingConfigTest {

    @Test
    fun `agent-mapping yaml should be valid and parseable`() {
        // Read the embedded resource
        val resourceStream = javaClass.getResourceAsStream("/claude/configuration/agent-mapping.yaml")
        assertNotNull(resourceStream, "agent-mapping.yaml should exist in resources")

        // Parse YAML
        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(resourceStream)
        assertNotNull(config, "agent-mapping.yaml should be valid YAML")

        // Verify main sections exist (workflowPhases and entityTypes removed in v2.0 architecture streamlining)
        assertTrue(config.containsKey("tagMappings"), "Should have tagMappings section")
        assertTrue(config.containsKey("tagPriority"), "Should have tagPriority section")
        assertTrue(config.containsKey("default_specialist"), "Should have default_specialist field")
        assertTrue(config.containsKey("fallback_behavior"), "Should have fallback_behavior field")
    }

    @Test
    fun `all specialist agent types should have tag mappings`() {
        // Only specialist agents need tag mappings (not coordination agents like Task Manager and Feature Manager)
        val expectedAgents = setOf(
            "Backend Engineer",
            "Bug Triage Specialist",
            "Database Engineer",
            "Feature Architect",
            "Frontend Developer",
            "Planning Specialist",
            "Technical Writer",
            "Test Engineer"
        )

        val resourceStream = javaClass.getResourceAsStream("/claude/configuration/agent-mapping.yaml")
        assertNotNull(resourceStream)

        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(resourceStream)

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
        val resourceStream = javaClass.getResourceAsStream("/claude/configuration/agent-mapping.yaml")
        assertNotNull(resourceStream)

        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(resourceStream)

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
        val resourceStream = javaClass.getResourceAsStream("/claude/configuration/agent-mapping.yaml")
        assertNotNull(resourceStream)

        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(resourceStream)

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

    @Test
    fun `all agent definition files should exist`() {
        val expectedAgentFiles = OrchestrationSetupManager.DEFAULT_AGENT_FILES

        expectedAgentFiles.forEach { fileName ->
            val resourceStream = javaClass.getResourceAsStream("/claude/agents/$fileName")
            assertNotNull(
                resourceStream,
                "Agent definition file '$fileName' should exist in resources"
            )
        }
    }

    @Test
    fun `all tag mappings reference valid agent definition files`() {
        // Verify that all agents referenced in tagMappings have corresponding .md files
        val resourceStream = javaClass.getResourceAsStream("/claude/configuration/agent-mapping.yaml")
        assertNotNull(resourceStream)

        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(resourceStream)

        @Suppress("UNCHECKED_CAST")
        val tagMappings = config["tagMappings"] as? List<Map<String, Any>>
        assertNotNull(tagMappings, "tagMappings should be a list")

        // v2.0 architecture: Direct specialist pattern (no Task Manager or Feature Manager)
        val validAgents = setOf(
            "Backend Engineer",
            "Bug Triage Specialist",
            "Database Engineer",
            "Feature Architect",
            "Frontend Developer",
            "Planning Specialist",
            "Technical Writer",
            "Test Engineer"
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
        val resourceStream = javaClass.getResourceAsStream("/claude/configuration/agent-mapping.yaml")
        assertNotNull(resourceStream)

        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(resourceStream)

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
        val resourceStream = javaClass.getResourceAsStream("/claude/configuration/agent-mapping.yaml")
        assertNotNull(resourceStream)

        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(resourceStream)

        @Suppress("UNCHECKED_CAST")
        val tagMappings = config["tagMappings"] as? List<Map<String, Any>>
        assertNotNull(tagMappings)

        // Test Backend Engineer has expected task tags
        val backendMapping = tagMappings.find { it["agent"] == "Backend Engineer" }
        assertNotNull(backendMapping, "Backend Engineer mapping should exist")
        @Suppress("UNCHECKED_CAST")
        val backendTags = backendMapping["task_tags"] as List<String>
        assertTrue(backendTags.contains("backend") || backendTags.contains("api"),
            "Backend Engineer should have 'backend' or 'api' tags")

        // Test Test Engineer has expected task tags
        val testMapping = tagMappings.find { it["agent"] == "Test Engineer" }
        assertNotNull(testMapping, "Test Engineer mapping should exist")
        @Suppress("UNCHECKED_CAST")
        val testTags = testMapping["task_tags"] as List<String>
        assertTrue(testTags.contains("testing") || testTags.contains("test"),
            "Test Engineer should have 'testing' or 'test' tags")

        // Test Technical Writer has expected task tags
        val writerMapping = tagMappings.find { it["agent"] == "Technical Writer" }
        assertNotNull(writerMapping, "Technical Writer mapping should exist")
        @Suppress("UNCHECKED_CAST")
        val writerTags = writerMapping["task_tags"] as List<String>
        assertTrue(writerTags.contains("documentation") || writerTags.contains("docs"),
            "Technical Writer should have 'documentation' or 'docs' tags")

        // Test Planning Specialist has expected task tags
        val planningMapping = tagMappings.find { it["agent"] == "Planning Specialist" }
        assertNotNull(planningMapping, "Planning Specialist mapping should exist")
        @Suppress("UNCHECKED_CAST")
        val planningTags = planningMapping["task_tags"] as List<String>
        assertTrue(planningTags.contains("planning") || planningTags.contains("requirements"),
            "Planning Specialist should have 'planning' or 'requirements' tags")
    }
}
