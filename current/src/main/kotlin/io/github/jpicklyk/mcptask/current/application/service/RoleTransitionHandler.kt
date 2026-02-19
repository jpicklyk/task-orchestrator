package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import java.time.Instant
import java.util.UUID

/**
 * Result of resolving a trigger to a target role.
 * Pure data -- no side effects.
 */
data class TransitionResolution(
    val success: Boolean,
    val targetRole: Role? = null,
    val statusLabel: String? = null,
    val error: String? = null
)

/**
 * Information about a single dependency that is blocking a transition.
 */
data class BlockerInfo(
    val itemId: UUID,
    val fromItemId: UUID,
    val currentRole: Role,
    val requiredRole: String
)

/**
 * Result of validating whether a transition is allowed given dependency constraints.
 */
data class TransitionValidation(
    val valid: Boolean,
    val error: String? = null,
    val blockers: List<BlockerInfo> = emptyList()
)

/**
 * Result of applying a transition (persisting role change + audit trail).
 */
data class TransitionApplyResult(
    val success: Boolean,
    val item: WorkItem? = null,
    val transition: RoleTransition? = null,
    val previousRole: Role? = null,
    val newRole: Role? = null,
    val error: String? = null
)

/**
 * Encapsulates all role transition logic for Current (v3).
 *
 * Three-phase workflow:
 * 1. **Resolve** — pure logic mapping trigger + current role to target role
 * 2. **Validate** — checks dependency constraints against the proposed transition
 * 3. **Apply** — persists the role change and records an audit trail entry
 *
 * The handler is stateless. Repository dependencies are passed as method parameters
 * so that the handler does not hold references to infrastructure.
 */
class RoleTransitionHandler {

    companion object {
        val VALID_TRIGGERS = setOf("start", "complete", "block", "hold", "resume", "cancel")
    }

    // -----------------------------------------------------------------------
    // Phase 1: Resolution (pure logic, no I/O)
    // -----------------------------------------------------------------------

    /**
     * Resolve a trigger to a target role using only the current role.
     *
     * For "resume" this overload cannot determine the restore target because it
     * needs the item's [WorkItem.previousRole]. Use [resolveTransition(WorkItem, String)]
     * for full context.
     */
    fun resolveTransition(currentRole: Role, trigger: String): TransitionResolution {
        return when (trigger.lowercase()) {
            "start" -> resolveStart(currentRole)
            "complete" -> resolveComplete(currentRole)
            "block", "hold" -> resolveBlock(currentRole)
            "resume" -> TransitionResolution(
                success = false,
                error = "Cannot resolve 'resume' without full WorkItem context (previousRole needed). " +
                        "Use resolveTransition(item, trigger) instead."
            )
            "cancel" -> resolveCancel(currentRole)
            else -> TransitionResolution(
                success = false,
                error = "Unknown trigger: '$trigger'. Valid triggers: ${VALID_TRIGGERS.joinToString()}"
            )
        }
    }

    /**
     * Resolve a trigger to a target role with full WorkItem context.
     * Required for "resume" which restores the item's [WorkItem.previousRole].
     *
     * @param hasReviewPhase When false and the item is in WORK role, the "start" trigger
     *   advances directly to TERMINAL instead of REVIEW. This allows schema-driven
     *   workflows to skip the REVIEW phase when no review notes are defined.
     */
    fun resolveTransition(item: WorkItem, trigger: String, hasReviewPhase: Boolean = true): TransitionResolution {
        return when (trigger.lowercase()) {
            "start" -> resolveStart(item.role, hasReviewPhase)
            "complete" -> resolveComplete(item.role)
            "block", "hold" -> resolveBlock(item.role)
            "resume" -> resolveResume(item)
            "cancel" -> resolveCancel(item.role)
            else -> TransitionResolution(
                success = false,
                error = "Unknown trigger: '$trigger'. Valid triggers: ${VALID_TRIGGERS.joinToString()}"
            )
        }
    }

    private fun resolveStart(currentRole: Role, hasReviewPhase: Boolean = true): TransitionResolution = when (currentRole) {
        Role.QUEUE -> TransitionResolution(success = true, targetRole = Role.WORK)
        Role.WORK -> if (hasReviewPhase) {
            TransitionResolution(success = true, targetRole = Role.REVIEW)
        } else {
            TransitionResolution(success = true, targetRole = Role.TERMINAL)
        }
        Role.REVIEW -> TransitionResolution(success = true, targetRole = Role.TERMINAL)
        Role.TERMINAL -> TransitionResolution(
            success = false,
            error = "Cannot start: item is already terminal"
        )
        Role.BLOCKED -> TransitionResolution(
            success = false,
            error = "Cannot start: item is blocked. Use 'resume' trigger first"
        )
    }

    private fun resolveComplete(currentRole: Role): TransitionResolution = when (currentRole) {
        Role.TERMINAL -> TransitionResolution(
            success = false,
            error = "Cannot complete: item is already terminal"
        )
        Role.BLOCKED -> TransitionResolution(
            success = false,
            error = "Cannot complete: item is blocked. Use 'resume' trigger first"
        )
        else -> TransitionResolution(success = true, targetRole = Role.TERMINAL)
    }

    private fun resolveBlock(currentRole: Role): TransitionResolution = when (currentRole) {
        Role.BLOCKED -> TransitionResolution(
            success = false,
            error = "Cannot block: item is already blocked"
        )
        Role.TERMINAL -> TransitionResolution(
            success = false,
            error = "Cannot block: item is already terminal"
        )
        else -> TransitionResolution(success = true, targetRole = Role.BLOCKED)
    }

    private fun resolveResume(item: WorkItem): TransitionResolution {
        if (item.role != Role.BLOCKED) {
            return TransitionResolution(
                success = false,
                error = "Cannot resume: item is not blocked (current role: ${item.role.name.lowercase()})"
            )
        }
        val restoreRole = item.previousRole
            ?: return TransitionResolution(
                success = false,
                error = "Cannot resume: item is blocked but has no previousRole to restore"
            )
        return TransitionResolution(success = true, targetRole = restoreRole)
    }

    private fun resolveCancel(currentRole: Role): TransitionResolution = when (currentRole) {
        Role.TERMINAL -> TransitionResolution(
            success = false,
            error = "Cannot cancel: item is already terminal"
        )
        else -> TransitionResolution(success = true, targetRole = Role.TERMINAL, statusLabel = "cancelled")
    }

    // -----------------------------------------------------------------------
    // Phase 2: Validation (reads dependencies, suspend for WorkItem lookups)
    // -----------------------------------------------------------------------

    /**
     * Validate that the proposed transition is allowed given dependency constraints.
     *
     * For forward progressions (QUEUE->WORK, WORK->REVIEW, etc.) this checks all
     * incoming BLOCKS/IS_BLOCKED_BY dependencies to ensure their blocker items have
     * reached the required [Dependency.effectiveUnblockRole] threshold.
     *
     * Transitions to BLOCKED skip dependency checks (blocking is always allowed).
     */
    suspend fun validateTransition(
        item: WorkItem,
        targetRole: Role,
        dependencyRepository: DependencyRepository,
        workItemRepository: WorkItemRepository
    ): TransitionValidation {
        // Blocking transitions always pass (no dependency gate)
        if (targetRole == Role.BLOCKED) {
            return TransitionValidation(valid = true)
        }

        // Terminal items cannot transition further
        if (item.role == Role.TERMINAL) {
            return TransitionValidation(
                valid = false,
                error = "Item is already terminal and cannot transition"
            )
        }

        // For forward progressions, check incoming blocking dependencies
        if (isForwardProgression(item.role, targetRole)) {
            val incomingDeps = dependencyRepository.findByToItemId(item.id)
            val blockers = mutableListOf<BlockerInfo>()

            for (dep in incomingDeps) {
                // RELATES_TO has no blocking semantics
                if (dep.type == DependencyType.RELATES_TO) continue

                val threshold = dep.effectiveUnblockRole() ?: continue
                val thresholdRole = Role.fromString(threshold) ?: continue

                // Determine which item is the blocker
                val blockerItemId = dep.fromItemId

                // Fetch the blocker's current state
                val blockerResult = workItemRepository.getById(blockerItemId)
                val blockerItem = when (blockerResult) {
                    is Result.Success -> blockerResult.data
                    is Result.Error -> {
                        // If the blocker item is missing, treat as unsatisfied
                        blockers.add(
                            BlockerInfo(
                                itemId = item.id,
                                fromItemId = blockerItemId,
                                currentRole = Role.QUEUE, // unknown, assume worst
                                requiredRole = threshold
                            )
                        )
                        continue
                    }
                }

                // Check whether the blocker has reached the threshold
                if (!Role.isAtOrBeyond(blockerItem.role, thresholdRole)) {
                    blockers.add(
                        BlockerInfo(
                            itemId = item.id,
                            fromItemId = blockerItemId,
                            currentRole = blockerItem.role,
                            requiredRole = threshold
                        )
                    )
                }
            }

            if (blockers.isNotEmpty()) {
                return TransitionValidation(
                    valid = false,
                    error = "${blockers.size} blocking dependency(ies) not yet satisfied",
                    blockers = blockers
                )
            }
        }

        return TransitionValidation(valid = true)
    }

    /**
     * Returns true if moving from [from] to [to] is a forward progression
     * along the QUEUE -> WORK -> REVIEW -> TERMINAL sequence.
     * Also returns true for jumps (e.g., QUEUE -> TERMINAL via "complete").
     * BLOCKED is orthogonal and always returns false.
     */
    internal fun isForwardProgression(from: Role, to: Role): Boolean {
        if (from == Role.BLOCKED || to == Role.BLOCKED) return false
        val fromIndex = Role.PROGRESSION.indexOf(from)
        val toIndex = Role.PROGRESSION.indexOf(to)
        if (fromIndex < 0 || toIndex < 0) return false
        return toIndex > fromIndex
    }

    // -----------------------------------------------------------------------
    // Phase 3: Apply (persist role change + audit trail)
    // -----------------------------------------------------------------------

    /**
     * Persist the role change on the WorkItem and record a [RoleTransition] audit entry.
     *
     * When transitioning to BLOCKED, the current role is saved as [WorkItem.previousRole]
     * so that "resume" can restore it later. When leaving BLOCKED, previousRole is cleared.
     *
     * @param item The current (pre-transition) WorkItem.
     * @param targetRole The resolved target role.
     * @param trigger The trigger that initiated this transition.
     * @param summary Optional human-readable summary.
     * @param statusLabel Optional display label (e.g., "cancelled").
     */
    suspend fun applyTransition(
        item: WorkItem,
        targetRole: Role,
        trigger: String,
        summary: String?,
        statusLabel: String?,
        workItemRepository: WorkItemRepository,
        roleTransitionRepository: RoleTransitionRepository
    ): TransitionApplyResult {
        val previousRole = item.role

        // Build the updated item via the update builder
        val updatedItem = item.update { current ->
            current.copy(
                role = targetRole,
                previousRole = when {
                    // When entering BLOCKED, save the current role for later resume
                    targetRole == Role.BLOCKED -> previousRole
                    // When leaving BLOCKED (resume), clear previousRole
                    previousRole == Role.BLOCKED -> null
                    // Otherwise preserve existing previousRole
                    else -> current.previousRole
                },
                statusLabel = when {
                    // Explicit statusLabel from resolution (e.g., "cancelled")
                    statusLabel != null -> statusLabel
                    // When entering BLOCKED, preserve existing statusLabel
                    targetRole == Role.BLOCKED -> current.statusLabel
                    // Normal forward progression clears statusLabel
                    else -> null
                },
                roleChangedAt = Instant.now()
            )
        }

        // Persist the item update
        return when (val result = workItemRepository.update(updatedItem)) {
            is Result.Success -> {
                // Record the audit trail
                val transition = RoleTransition(
                    itemId = item.id,
                    fromRole = previousRole.name.lowercase(),
                    toRole = targetRole.name.lowercase(),
                    fromStatusLabel = item.statusLabel,
                    toStatusLabel = statusLabel,
                    trigger = trigger,
                    summary = summary
                )
                roleTransitionRepository.create(transition)

                TransitionApplyResult(
                    success = true,
                    item = result.data,
                    transition = transition,
                    previousRole = previousRole,
                    newRole = targetRole
                )
            }
            is Result.Error -> {
                TransitionApplyResult(
                    success = false,
                    error = "Failed to update item: ${result.error.message}"
                )
            }
        }
    }
}
