package io.github.jpicklyk.mcptask.domain.model.workflow

/**
 * Configuration for a workflow flow.
 * Flows define status progressions and event handlers for specific workflows.
 *
 * Examples:
 * - default_flow: Standard development workflow
 * - rapid_prototype_flow: Fast iteration, skips testing
 * - with_review_flow: Adds manual review gate
 * - research_flow: Custom academic workflow
 *
 * @property name Flow identifier (e.g., "default_flow", "rapid_prototype_flow")
 * @property statuses Ordered list of statuses in this flow
 * @property tags Entity tags that trigger this flow (null = fallback flow)
 * @property eventHandlers Map of event names to their handlers
 * @property eventOverrides Map of event names to flow-specific overrides
 */
data class FlowConfig(
    val name: String,
    val statuses: List<String>,
    val tags: List<String>? = null,
    val eventHandlers: Map<String, EventHandler> = emptyMap(),
    val eventOverrides: Map<String, EventOverride>? = null
)

/**
 * Event handler configuration defining status transitions for events.
 *
 * @property from Source status or list of source statuses (normalized format)
 * @property to Target status (normalized format)
 * @property auto Whether transition is automatic (true) or requires user confirmation (false)
 * @property validation Optional validation type identifier
 */
data class EventHandler(
    val from: String,
    val to: String,
    val auto: Boolean,
    val validation: String? = null
)

/**
 * Event override for flow-specific behavior.
 * Allows flows to customize event handlers without duplicating full configuration.
 *
 * Example: rapid_prototype_flow overrides "all_tasks_complete" to skip testing
 *
 * @property from Source status (normalized format)
 * @property to Target status (normalized format)
 */
data class EventOverride(
    val from: String,
    val to: String
)
