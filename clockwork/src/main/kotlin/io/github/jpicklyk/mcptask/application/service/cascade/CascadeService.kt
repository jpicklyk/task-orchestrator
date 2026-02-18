package io.github.jpicklyk.mcptask.application.service.cascade

import io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade
import io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig
import io.github.jpicklyk.mcptask.domain.model.workflow.CascadeEvent
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.model.workflow.UnblockedTask
import java.util.UUID

/**
 * Service for cascade event detection, application, and related workflow operations.
 *
 * Consolidates cascade logic previously split across WorkflowServiceImpl (detection),
 * RequestTransitionTool (application), and ManageContainerTool (detection-only).
 * Uses StatusProgressionService for flow/status resolution instead of WorkflowConfigLoader.
 */
interface CascadeService {

    /**
     * Detects cascade events triggered by a status change on the given entity.
     * Pure detection — does NOT apply any transitions.
     *
     * Cascade events detected:
     * - `first_task_started`: First task moves to in-progress AND feature at first flow status
     * - `all_tasks_complete`: All tasks in feature completed/cancelled
     * - `all_features_complete`: All features in project reached terminal status
     *
     * @return List of detected cascade events (may be empty)
     */
    suspend fun detectCascadeEvents(containerId: UUID, containerType: ContainerType): List<CascadeEvent>

    /**
     * Detects and applies cascade events recursively with depth limiting.
     * For each detected event: validates transition, applies status change,
     * runs completion cleanup (if feature terminal), and recurses.
     *
     * @param depth Current recursion depth (start at 0)
     * @param maxDepth Maximum cascade depth (from AutoCascadeConfig, typically 3)
     * @return List of applied (or failed) cascade results
     */
    suspend fun applyCascades(
        containerId: UUID,
        containerType: String,
        depth: Int = 0,
        maxDepth: Int = 3
    ): List<AppliedCascade>

    /**
     * Finds downstream tasks that become fully unblocked after a task completion/cancellation.
     * Checks all outgoing BLOCKS dependencies and verifies ALL incoming blockers are resolved.
     *
     * @return List of newly unblocked tasks (taskId + title)
     */
    suspend fun findNewlyUnblockedTasks(completedTaskId: UUID): List<UnblockedTask>

    /**
     * Loads auto-cascade configuration from .taskorchestrator/config.yaml.
     * Falls back to bundled default-config.yaml, then to AutoCascadeConfig defaults.
     */
    fun loadAutoCascadeConfig(): AutoCascadeConfig
}
