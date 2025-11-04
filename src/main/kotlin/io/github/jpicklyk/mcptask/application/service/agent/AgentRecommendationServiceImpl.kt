package io.github.jpicklyk.mcptask.application.service.agent

import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.infrastructure.filesystem.AgentDirectoryManager
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileNotFoundException

/**
 * Implementation of AgentRecommendationService that reads configuration from agent-mapping.yaml
 */
class AgentRecommendationServiceImpl(
    private val agentDirectoryManager: AgentDirectoryManager
) : AgentRecommendationService {

    private val logger = LoggerFactory.getLogger(AgentRecommendationServiceImpl::class.java)
    private val yaml = Yaml()

    /**
     * Data class representing the agent mapping configuration structure
     */
    private data class AgentMappingConfig(
        val workflowPhases: Map<String, String> = emptyMap(),
        val tagMappings: List<TagMapping> = emptyList(),
        val tagPriority: List<String> = emptyList(),
        val entityTypes: Map<String, EntityMapping> = emptyMap(),
        val defaultSpecialist: String? = null,
        val fallbackBehavior: String = "skip"
    )

    private data class TagMapping(
        val task_tags: List<String> = emptyList(),
        val agent: String = "",
        val section_tags: List<String> = emptyList()
    )

    private data class EntityMapping(
        val planning: String? = null,
        val fallback: String? = null
    )

    override fun recommendAgent(task: Task): AgentRecommendation? {
        val config = loadAgentMapping() ?: return null

        // Get task tags as lowercase for case-insensitive matching
        val taskTags = task.tags.map { it.lowercase().trim() }

        logger.debug("Recommending agent for task ${task.id} with tags: $taskTags")

        // Try to match based on tag priority order first
        for (priorityTag in config.tagPriority) {
            if (taskTags.contains(priorityTag.lowercase())) {
                // Find the mapping for this priority tag
                val mapping = config.tagMappings.find { mapping ->
                    mapping.task_tags.any { it.lowercase() == priorityTag.lowercase() }
                }

                if (mapping != null) {
                    val matchedTags = taskTags.filter { taskTag ->
                        mapping.task_tags.any { it.lowercase() == taskTag.lowercase() }
                    }

                    logger.info("Recommended agent '${mapping.agent}' for task ${task.id} based on priority tag: $priorityTag")

                    return AgentRecommendation(
                        agentName = mapping.agent,
                        reason = "Task tags match priority category: $priorityTag (matched: ${matchedTags.joinToString(", ")})",
                        matchedTags = matchedTags,
                        sectionTags = mapping.section_tags
                    )
                }
            }
        }

        // If no priority match, try matching any tag
        for (mapping in config.tagMappings) {
            val matchedTags = taskTags.filter { taskTag ->
                mapping.task_tags.any { it.lowercase() == taskTag.lowercase() }
            }

            if (matchedTags.isNotEmpty()) {
                logger.info("Recommended agent '${mapping.agent}' for task ${task.id} based on tags: $matchedTags")

                return AgentRecommendation(
                    agentName = mapping.agent,
                    reason = "Task tags match: ${matchedTags.joinToString(", ")}",
                    matchedTags = matchedTags,
                    sectionTags = mapping.section_tags
                )
            }
        }

        logger.debug("No agent recommendation for task ${task.id} - no tag matches found")

        // Check fallback behavior when no tags match
        if (config.fallbackBehavior == "use_default" && !config.defaultSpecialist.isNullOrBlank()) {
            logger.info("Using default specialist '${config.defaultSpecialist}' for task ${task.id} (fallback)")

            // Find section tags for the default specialist
            val defaultMapping = config.tagMappings.find { it.agent == config.defaultSpecialist }
            val sectionTags = defaultMapping?.section_tags ?: emptyList()

            return AgentRecommendation(
                agentName = config.defaultSpecialist,
                reason = "No matching tags found. Using default specialist from configuration.",
                matchedTags = emptyList(),
                sectionTags = sectionTags
            )
        }

        return null
    }

    override fun listAvailableAgents(): List<String> {
        val config = loadAgentMapping()

        if (config == null) {
            // Fallback to listing agent files from directory
            return agentDirectoryManager.listAgentFiles()
                .map { it.removeSuffix(".md") }
        }

        // Extract unique agent names from config
        val agents = mutableSetOf<String>()

        // From workflow phases
        agents.addAll(config.workflowPhases.values)

        // From tag mappings
        agents.addAll(config.tagMappings.map { it.agent })

        // From entity types
        config.entityTypes.values.forEach { mapping ->
            mapping.planning?.let { agents.add(it) }
        }

        return agents.sorted()
    }

    override fun getSectionTagsForAgent(agentName: String): List<String> {
        val config = loadAgentMapping() ?: return emptyList()

        // Find the first tag mapping that uses this agent
        val mapping = config.tagMappings.find { it.agent == agentName }

        return mapping?.section_tags ?: emptyList()
    }

    /**
     * Load and parse the agent-mapping.yaml file.
     * Returns null if file doesn't exist or can't be parsed.
     */
    private fun loadAgentMapping(): AgentMappingConfig? {
        return try {
            val content = agentDirectoryManager.readAgentMappingFile()

            if (content == null) {
                logger.warn("Agent mapping file not found. Run setup_agents tool to initialize.")
                return null
            }

            val rawData = yaml.load<Map<String, Any>>(content)

            // Parse the YAML structure
            AgentMappingConfig(
                workflowPhases = parseWorkflowPhases(rawData),
                tagMappings = parseTagMappings(rawData),
                tagPriority = parseTagPriority(rawData),
                entityTypes = parseEntityTypes(rawData),
                defaultSpecialist = rawData["default_specialist"] as? String,
                fallbackBehavior = (rawData["fallback_behavior"] as? String) ?: "skip"
            )
        } catch (e: FileNotFoundException) {
            logger.warn("Agent mapping file not found: ${e.message}")
            null
        } catch (e: Exception) {
            logger.error("Failed to load agent mapping configuration", e)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseWorkflowPhases(rawData: Map<String, Any>): Map<String, String> {
        val phases = rawData["workflowPhases"] as? Map<String, String>
        return phases ?: emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTagMappings(rawData: Map<String, Any>): List<TagMapping> {
        val mappings = rawData["tagMappings"] as? List<Map<String, Any>>
        return mappings?.mapNotNull { mapping ->
            try {
                TagMapping(
                    task_tags = (mapping["task_tags"] as? List<String>) ?: emptyList(),
                    agent = mapping["agent"] as? String ?: "",
                    section_tags = (mapping["section_tags"] as? List<String>) ?: emptyList()
                )
            } catch (e: Exception) {
                logger.warn("Failed to parse tag mapping: ${e.message}")
                null
            }
        } ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTagPriority(rawData: Map<String, Any>): List<String> {
        val priority = rawData["tagPriority"] as? List<String>
        return priority ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEntityTypes(rawData: Map<String, Any>): Map<String, EntityMapping> {
        val entityTypes = rawData["entityTypes"] as? Map<String, Any?>
        return entityTypes?.mapNotNull { (key, value) ->
            // Skip entries where value is null (only comments in YAML, no key-value pairs)
            val valueMap = value as? Map<String, String> ?: return@mapNotNull null
            key to EntityMapping(
                planning = valueMap["planning"],
                fallback = valueMap["fallback"]
            )
        }?.toMap() ?: emptyMap()
    }
}
