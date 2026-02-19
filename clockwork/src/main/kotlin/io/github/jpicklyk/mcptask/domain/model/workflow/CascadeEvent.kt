package io.github.jpicklyk.mcptask.domain.model.workflow

import java.util.UUID

/**
 * Represents a cascade event detected by the workflow service.
 * Cascade events occur when completing one entity should trigger progression of a parent entity.
 *
 * Examples:
 * - Task completed → Feature should progress (all_tasks_complete)
 * - Feature completed → Project should progress (all_features_complete)
 * - First task started → Feature should activate (first_task_started)
 *
 * @property event Event identifier (e.g., "all_tasks_complete", "first_task_started")
 * @property targetType Type of entity that should cascade (TASK, FEATURE, PROJECT)
 * @property targetId UUID of the target entity
 * @property targetName Human-readable name of the target entity
 * @property currentStatus Current status of the target entity (normalized format)
 * @property suggestedStatus Suggested next status from workflow config
 * @property flow Active workflow flow (e.g., "default_flow", "rapid_prototype_flow")
 * @property automatic Whether transition should be automatic (true) or require user confirmation (false)
 * @property reason Human-readable explanation of why this event was detected
 */
data class CascadeEvent(
    val event: String,
    val targetType: ContainerType,
    val targetId: UUID,
    val targetName: String,
    val currentStatus: String,
    val suggestedStatus: String,
    val flow: String,
    val automatic: Boolean,
    val reason: String
)

/**
 * Container type for cascade events.
 */
enum class ContainerType {
    TASK,
    FEATURE,
    PROJECT
}
