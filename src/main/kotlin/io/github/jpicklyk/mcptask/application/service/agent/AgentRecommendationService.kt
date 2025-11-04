package io.github.jpicklyk.mcptask.application.service.agent

import io.github.jpicklyk.mcptask.domain.model.Task

/**
 * Service for recommending AI agents based on task metadata.
 */
interface AgentRecommendationService {
    /**
     * Recommend an agent for the given task based on tags and metadata.
     *
     * @param task The task to recommend an agent for
     * @return AgentRecommendation with agent name, reason, and section tags, or null if no agent recommended
     */
    fun recommendAgent(task: Task): AgentRecommendation?

    /**
     * List all available agents from configuration.
     *
     * @return List of available agent names
     */
    fun listAvailableAgents(): List<String>

    /**
     * Get section tags that the given agent should query for.
     *
     * @param agentName The name of the agent
     * @return List of section tags the agent should look for
     */
    fun getSectionTagsForAgent(agentName: String): List<String>
}

/**
 * Represents an agent recommendation with reasoning and context.
 */
data class AgentRecommendation(
    val agentName: String,
    val reason: String,
    val matchedTags: List<String>,
    val sectionTags: List<String>
)
