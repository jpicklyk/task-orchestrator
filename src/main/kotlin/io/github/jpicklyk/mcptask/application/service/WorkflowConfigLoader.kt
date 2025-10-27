package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler
import io.github.jpicklyk.mcptask.domain.model.workflow.FlowConfig
import io.github.jpicklyk.mcptask.domain.model.workflow.WorkflowConfig

/**
 * Service for loading and caching workflow configuration from status_workflow_config.yaml.
 *
 * Configuration file location: .taskorchestrator/status_workflow_config.yaml
 * Uses AGENT_CONFIG_DIR environment variable for Docker compatibility.
 *
 * Provides access to:
 * - Flow mappings (default_flow, rapid_prototype_flow, custom flows)
 * - Event handlers (first_task_started, all_tasks_complete, etc.)
 * - Status progressions (task, feature, project)
 *
 * Configuration is cached in memory with file modification checking.
 * Falls back to hardcoded defaults if config file doesn't exist.
 */
interface WorkflowConfigLoader {
    /**
     * Gets all flow mappings from configuration.
     *
     * @return Map of flow name to FlowConfig
     */
    fun getFlowMappings(): Map<String, FlowConfig>

    /**
     * Gets a specific flow configuration by name.
     * Falls back to "default_flow" if specified flow doesn't exist.
     *
     * @param flowName Name of the flow
     * @return FlowConfig for the specified flow
     */
    fun getFlowConfig(flowName: String): FlowConfig

    /**
     * Gets event handlers for a specific flow.
     *
     * @param flowName Name of the flow
     * @return Map of event name to EventHandler
     */
    fun getEventHandlers(flowName: String): Map<String, EventHandler>

    /**
     * Gets status progressions for all container types.
     *
     * @return Map of container type ("task", "feature", "project") to list of statuses
     */
    fun getStatusProgressions(): Map<String, List<String>>

    /**
     * Reloads configuration from disk (forces cache invalidation).
     * Useful for testing or manual config updates.
     */
    fun reloadConfig()
}
