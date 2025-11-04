package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.workflow.CascadeEvent
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.model.workflow.WorkflowState
import java.util.UUID

/**
 * Service for workflow management and cascade event detection.
 *
 * This service provides config-driven workflow orchestration:
 * - Detects cascade events (e.g., all_tasks_complete triggers feature progression)
 * - Determines active flow based on entity tags
 * - Provides complete workflow state for entities
 *
 * CRITICAL: This service uses config-driven logic, NOT hardcoded enum checks.
 * All status comparisons use normalized strings from status_workflow_config.yaml.
 */
interface WorkflowService {
    /**
     * Detects cascade events triggered by status change of given container.
     *
     * Cascade events occur when completing one entity should trigger progression
     * of a parent entity. Examples:
     * - Task completed → Feature should progress (all_tasks_complete)
     * - Feature completed → Project should progress (all_features_complete)
     * - First task started → Feature should activate (first_task_started)
     *
     * Detection is deterministic and config-driven:
     * - Queries workflow config for event handlers
     * - Compares normalized statuses (no enum checks)
     * - Returns events based on config rules
     *
     * @param containerId UUID of task/feature/project that changed
     * @param containerType Type of container
     * @return List of cascade events detected (empty if none)
     */
    suspend fun detectCascadeEvents(
        containerId: UUID,
        containerType: ContainerType
    ): List<CascadeEvent>

    /**
     * Determines active workflow flow based on entity tags.
     *
     * Flow selection algorithm:
     * 1. Check flow_mappings in config for tag matches
     * 2. Return first flow where entity tags match flow tags
     * 3. Fall back to "default_flow" if no matches
     *
     * Examples:
     * - Tags: ["prototype"] → rapid_prototype_flow
     * - Tags: ["security", "backend"] → with_review_flow
     * - Tags: ["research"] → research_flow (if defined in config)
     * - Tags: [] → default_flow
     *
     * @param tags Entity tags
     * @return Flow name (e.g., "default_flow", "rapid_prototype_flow")
     */
    fun determineActiveFlow(tags: List<String>): String

    /**
     * Validates if entity can transition to target status.
     * Delegates to StatusValidator for prerequisite checking.
     *
     * @param containerId Entity UUID
     * @param containerType Entity type
     * @param targetStatus Status to transition to
     * @return ValidationResult with prerequisites check
     */
    suspend fun validatePrerequisites(
        containerId: UUID,
        containerType: ContainerType,
        targetStatus: String
    ): StatusValidator.ValidationResult

    /**
     * Gets complete workflow state for entity.
     *
     * Returns comprehensive workflow information:
     * - Active flow based on tags
     * - Current status (normalized)
     * - Allowed transitions from config
     * - Detected cascade events
     * - Prerequisite validation for all transitions
     *
     * Used by query_workflow_state tool to provide all workflow info in one call.
     *
     * @param containerId Entity UUID
     * @param containerType Entity type
     * @return WorkflowState with flow, events, transitions, prerequisites
     */
    suspend fun getWorkflowState(
        containerId: UUID,
        containerType: ContainerType
    ): WorkflowState
}
