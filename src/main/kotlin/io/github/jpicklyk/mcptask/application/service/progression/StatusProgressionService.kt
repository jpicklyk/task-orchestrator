package io.github.jpicklyk.mcptask.application.service.progression

import java.util.UUID

/**
 * Service for intelligent status progression recommendations.
 *
 * This service provides AI-assisted status recommendations by analyzing:
 * - Entity tags to determine active workflow flow
 * - Current status position in the flow
 * - Prerequisite readiness (via StatusValidator integration)
 * - Terminal status blocking
 *
 * Designed to support Status Progression Skill and other workflow automation tools.
 *
 * Usage example:
 * ```
 * val result = statusProgressionService.getNextStatus(
 *     currentStatus = "in-progress",
 *     containerType = "task",
 *     tags = listOf("bug", "backend"),
 *     containerId = taskId
 * )
 *
 * when (result) {
 *     is NextStatusRecommendation.Ready -> {
 *         // Apply recommended status: result.recommendedStatus
 *         // Show flow context: result.activeFlow, result.position
 *     }
 *     is NextStatusRecommendation.Blocked -> {
 *         // Cannot progress: result.blockers
 *     }
 *     is NextStatusRecommendation.Terminal -> {
 *         // Already at terminal status
 *     }
 * }
 * ```
 */
interface StatusProgressionService {

    /**
     * Get recommended next status based on current state and workflow flow.
     *
     * Algorithm:
     * 1. Determine active flow based on tags (via StatusValidator.getActiveFlow)
     * 2. Find current status position in flow
     * 3. Identify next status in sequence
     * 4. Check readiness via StatusValidator prerequisite validation
     * 5. Return recommendation with flow context
     *
     * @param currentStatus Current status of the entity (e.g., "in-progress", "testing")
     * @param containerType Container type ("project", "feature", or "task")
     * @param tags Entity tags used to determine active workflow (e.g., ["bug", "backend"])
     * @param containerId Optional entity ID for prerequisite validation (if null, skips readiness checks)
     * @return NextStatusRecommendation with recommendation details or blocking reasons
     */
    suspend fun getNextStatus(
        currentStatus: String,
        containerType: String,
        tags: List<String> = emptyList(),
        containerId: UUID? = null
    ): NextStatusRecommendation

    /**
     * Get complete flow path for current entity.
     *
     * Returns the full workflow path based on entity tags, showing:
     * - Active flow name (e.g., "bug_fix_flow")
     * - Complete status sequence
     * - Current position in flow
     * - Terminal statuses
     * - Emergency transitions
     *
     * Useful for visualization and progress tracking.
     *
     * @param containerType Container type ("project", "feature", or "task")
     * @param tags Entity tags used to determine active workflow
     * @param currentStatus Optional current status to mark position in flow
     * @return FlowPath with complete workflow information
     */
    fun getFlowPath(
        containerType: String,
        tags: List<String> = emptyList(),
        currentStatus: String? = null
    ): FlowPath

    /**
     * Check readiness to transition to a specific status.
     *
     * Integrates with StatusValidator to check:
     * - Is transition valid in current flow?
     * - Are prerequisites met? (tasks completed, summary populated, etc.)
     * - Is current status terminal?
     * - Is target status in active flow?
     *
     * @param currentStatus Current status of the entity
     * @param targetStatus Target status to check
     * @param containerType Container type ("project", "feature", or "task")
     * @param tags Entity tags for flow determination
     * @param containerId Entity ID for prerequisite validation (required)
     * @return ReadinessResult with validation details
     */
    suspend fun checkReadiness(
        currentStatus: String,
        targetStatus: String,
        containerType: String,
        tags: List<String> = emptyList(),
        containerId: UUID
    ): ReadinessResult
}

/**
 * Result of next status recommendation.
 */
sealed class NextStatusRecommendation {

    /**
     * Entity is ready to progress to next status.
     *
     * @property recommendedStatus Next status in workflow sequence
     * @property activeFlow Name of the active workflow (e.g., "bug_fix_flow", "default_flow")
     * @property flowSequence Complete status sequence for active flow
     * @property currentPosition Index of current status in flow (0-based)
     * @property matchedTags Tags that matched to determine flow (empty if default flow)
     * @property reason Human-readable explanation of recommendation
     */
    data class Ready(
        val recommendedStatus: String,
        val activeFlow: String,
        val flowSequence: List<String>,
        val currentPosition: Int,
        val matchedTags: List<String>,
        val reason: String
    ) : NextStatusRecommendation()

    /**
     * Entity cannot progress due to blockers.
     *
     * @property currentStatus Current status
     * @property blockers List of blocking reasons (e.g., "Summary required", "3 tasks incomplete")
     * @property activeFlow Name of the active workflow
     * @property flowSequence Complete status sequence for active flow
     * @property currentPosition Index of current status in flow (0-based)
     */
    data class Blocked(
        val currentStatus: String,
        val blockers: List<String>,
        val activeFlow: String,
        val flowSequence: List<String>,
        val currentPosition: Int
    ) : NextStatusRecommendation()

    /**
     * Entity is at terminal status (cannot progress further).
     *
     * @property terminalStatus Current terminal status (e.g., "completed", "cancelled")
     * @property activeFlow Name of the active workflow
     * @property reason Human-readable explanation
     */
    data class Terminal(
        val terminalStatus: String,
        val activeFlow: String,
        val reason: String
    ) : NextStatusRecommendation()
}

/**
 * Complete workflow path information.
 *
 * @property activeFlow Name of the active workflow (e.g., "bug_fix_flow", "default_flow")
 * @property flowSequence Complete status sequence for active flow
 * @property currentPosition Index of current status in flow (0-based, null if status not provided)
 * @property matchedTags Tags that matched to determine flow (empty if default flow)
 * @property terminalStatuses Terminal statuses for this container type
 * @property emergencyTransitions Emergency transitions available from any status
 */
data class FlowPath(
    val activeFlow: String,
    val flowSequence: List<String>,
    val currentPosition: Int?,
    val matchedTags: List<String>,
    val terminalStatuses: List<String>,
    val emergencyTransitions: List<String>
)

/**
 * Result of readiness check for status transition.
 */
sealed class ReadinessResult {

    /**
     * Entity is ready for transition.
     *
     * @property isValid Transition is valid in current flow
     * @property reason Human-readable explanation
     */
    data class Ready(
        val isValid: Boolean,
        val reason: String
    ) : ReadinessResult()

    /**
     * Entity is not ready due to blockers.
     *
     * @property blockers List of blocking reasons preventing transition
     * @property suggestions Optional suggestions to resolve blockers
     */
    data class NotReady(
        val blockers: List<String>,
        val suggestions: List<String> = emptyList()
    ) : ReadinessResult()

    /**
     * Transition is not valid (status not in flow, terminal status blocking, etc.).
     *
     * @property reason Human-readable explanation of why transition is invalid
     * @property allowedStatuses List of valid next statuses from current position
     */
    data class Invalid(
        val reason: String,
        val allowedStatuses: List<String> = emptyList()
    ) : ReadinessResult()
}
