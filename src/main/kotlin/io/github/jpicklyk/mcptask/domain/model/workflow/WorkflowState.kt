package io.github.jpicklyk.mcptask.domain.model.workflow

import java.util.UUID

/**
 * Represents the complete workflow state for a task, feature, or project.
 * Used by query_workflow_state tool to provide comprehensive workflow information.
 *
 * @property containerId UUID of the entity
 * @property containerType Type of entity (TASK, FEATURE, PROJECT)
 * @property currentStatus Current status (normalized format)
 * @property activeFlow Active workflow flow based on entity tags
 * @property allowedTransitions List of statuses that can be transitioned to from current status
 * @property detectedEvents List of cascade events detected for this entity
 * @property prerequisites Map of target statuses to their prerequisite validation results
 */
data class WorkflowState(
    val containerId: UUID,
    val containerType: ContainerType,
    val currentStatus: String,
    val activeFlow: String,
    val allowedTransitions: List<String>,
    val detectedEvents: List<CascadeEvent>,
    val prerequisites: Map<String, PrerequisiteResult>
)

/**
 * Result of prerequisite validation for a specific status transition.
 *
 * @property status Target status being validated
 * @property met Whether all prerequisites are satisfied
 * @property requirements List of prerequisite requirements
 * @property blockingReasons List of reasons why prerequisites are not met (empty if met)
 */
data class PrerequisiteResult(
    val status: String,
    val met: Boolean,
    val requirements: List<String>,
    val blockingReasons: List<String> = emptyList()
)
