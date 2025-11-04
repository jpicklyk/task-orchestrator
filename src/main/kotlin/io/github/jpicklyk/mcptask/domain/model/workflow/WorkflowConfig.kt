package io.github.jpicklyk.mcptask.domain.model.workflow

/**
 * Top-level workflow configuration container.
 * Loaded from .taskorchestrator/status_workflow_config.yaml
 *
 * This configuration defines:
 * - Status progressions for each container type
 * - Workflow flows (default, rapid_prototype, custom flows)
 * - Event handlers for status transitions
 *
 * @property statusProgressions Map of container type to list of allowed statuses
 * @property flowMappings Map of flow name to flow configuration
 * @property validationConfig Optional validation settings (e.g., validate_prerequisites)
 */
data class WorkflowConfig(
    val statusProgressions: Map<String, List<String>>,
    val flowMappings: Map<String, FlowConfig>,
    val validationConfig: Map<String, Any>? = null
)
