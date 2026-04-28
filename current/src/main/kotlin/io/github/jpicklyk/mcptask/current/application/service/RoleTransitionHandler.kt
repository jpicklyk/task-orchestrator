package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.ActorAware
import io.github.jpicklyk.mcptask.current.application.tools.PolicyResolution
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import java.time.Instant
import java.util.UUID

/**
 * Result of a high-level entry-point transition (userTransition / cascadeTransition).
 * Wraps the lower-level [TransitionApplyResult] and adds resolution-phase context.
 */
data class EntryPointTransitionResult(
    val success: Boolean,
    val item: WorkItem? = null,
    val previousRole: Role? = null,
    val newRole: Role? = null,
    val trigger: String? = null,
    val error: String? = null
)

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
 * Result of an ownership check for a [UserTrigger]-based transition.
 *
 * - [Allowed] — the caller is allowed to transition this item (unclaimed, expired, or caller holds the claim).
 * - [Rejected] — the caller does not hold the active claim; operation must be refused.
 * - [PolicyRejected] — the configured [DegradedModePolicy] rejected the actor verification; operation must be refused.
 */
sealed class OwnershipCheckResult {
    /** Caller is allowed to proceed with the transition. */
    data object Allowed : OwnershipCheckResult()

    /**
     * The item has an active (non-expired) claim held by a different agent.
     * [claimedBy] is the current holder (for internal logging only — do not surface to callers).
     */
    data class Rejected(
        val error: String
    ) : OwnershipCheckResult()

    /**
     * The [DegradedModePolicy] rejected the actor's verification status; operation must be refused.
     * [reason] is a human-readable explanation from the policy.
     */
    data class PolicyRejected(
        val reason: String
    ) : OwnershipCheckResult()
}

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
        /** Triggers accepted from external callers. "cascade" is system-internal. */
        val USER_TRIGGERS = setOf("start", "complete", "block", "hold", "resume", "cancel", "reopen")
    }

    // -----------------------------------------------------------------------
    // High-level entry points
    // -----------------------------------------------------------------------

    /**
     * Check whether [actorClaim] is allowed to transition [item].
     *
     * Ownership rule:
     * - If the item is unclaimed (`claimedBy == null`) or the claim has expired
     *   (`claimExpiresAt <= now()`), any caller may proceed regardless of actor identity.
     * - If the item has an active (non-expired) claim, the caller's trusted actor id
     *   (resolved via [DegradedModePolicy]) must match `item.claimedBy`.
     * - If no [actorClaim] is provided and the item has an active claim, the operation
     *   is rejected (the item is owned by another agent).
     *
     * Cascade transitions bypass this check entirely — they are initiated by the system,
     * not by an external caller, and always pass through [cascadeTransition] instead.
     *
     * @param item The current work item (freshly read from the DB).
     * @param actorClaim The actor claim extracted from the request, or null if absent.
     * @param verification The verification result from [ActorVerifier.verify], or null.
     * @param degradedModePolicy The deployment's identity degraded-mode policy.
     * @return [OwnershipCheckResult.Allowed], [OwnershipCheckResult.Rejected], or
     *         [OwnershipCheckResult.PolicyRejected].
     */
    fun checkOwnershipForTransition(
        item: WorkItem,
        actorClaim: ActorClaim?,
        verification: VerificationResult?,
        degradedModePolicy: DegradedModePolicy
    ): OwnershipCheckResult {
        val now = Instant.now()

        // Determine whether the item has an active (non-expired) claim.
        val hasActiveClaim =
            item.claimedBy != null &&
                item.claimExpiresAt != null &&
                item.claimExpiresAt.isAfter(now)

        if (!hasActiveClaim) {
            // Item is unclaimed or claim has expired — any caller may proceed.
            // If an actor was provided, still apply policy check so REJECT policy works.
            if (actorClaim != null && verification != null) {
                val policyResult = ActorAware.resolveTrustedActorId(actorClaim, verification, degradedModePolicy)
                if (policyResult is PolicyResolution.Rejected) {
                    return OwnershipCheckResult.PolicyRejected(policyResult.reason)
                }
            }
            return OwnershipCheckResult.Allowed
        }

        // Item has an active claim — check that the caller holds it.
        if (actorClaim == null || verification == null) {
            return OwnershipCheckResult.Rejected(
                "Item is claimed by another agent and cannot be transitioned without providing " +
                    "actor credentials. Claim expires at ${item.claimExpiresAt}."
            )
        }

        // Resolve the trusted actor id via policy.
        val policyResult = ActorAware.resolveTrustedActorId(actorClaim, verification, degradedModePolicy)
        if (policyResult is PolicyResolution.Rejected) {
            return OwnershipCheckResult.PolicyRejected(policyResult.reason)
        }
        val trustedId = (policyResult as PolicyResolution.Trusted).trustedId

        // Compare trusted caller id to the current claim holder.
        return if (trustedId == item.claimedBy) {
            OwnershipCheckResult.Allowed
        } else {
            OwnershipCheckResult.Rejected(
                "Ownership check failed: this item is claimed by a different agent. " +
                    "Claim expires at ${item.claimExpiresAt}. " +
                    "Release the claim or wait for it to expire before transitioning."
            )
        }
    }

    /**
     * Public entry point for transitions initiated by an external caller (agent, user,
     * orchestrator) through the `advance_item` MCP tool.
     *
     * Accepts only [UserTrigger] values — "cascade" is not a valid [UserTrigger] and
     * therefore cannot reach this path.
     *
     * Enforces claim ownership: if the item has an active (non-expired) claim, the caller's
     * resolved actor id must match `item.claimedBy`. Unclaimed and expired-claim items
     * are accessible to any caller. See [checkOwnershipForTransition] for the full rule set.
     *
     * @param item The current work item.
     * @param trigger The validated [UserTrigger] from the public API.
     * @param summary Optional human-readable transition summary.
     * @param statusLabel Optional display label override (e.g., "cancelled").
     * @param hasReviewPhase When false and the item is in WORK, start advances to TERMINAL.
     * @param workItemRepository Repository for persisting item updates.
     * @param roleTransitionRepository Repository for recording the audit trail.
     * @param dependencyRepository Repository for validating dependency constraints.
     * @param actorClaim Optional actor attribution (populated from verified request context).
     * @param verification Optional verification result attached to the actor claim.
     * @param degradedModePolicy The deployment's identity degraded-mode policy.
     */
    suspend fun userTransition(
        item: WorkItem,
        trigger: UserTrigger,
        summary: String?,
        statusLabel: String?,
        hasReviewPhase: Boolean,
        workItemRepository: WorkItemRepository,
        roleTransitionRepository: RoleTransitionRepository,
        dependencyRepository: DependencyRepository,
        actorClaim: ActorClaim? = null,
        verification: VerificationResult? = null,
        degradedModePolicy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED
    ): EntryPointTransitionResult {
        // Ownership check: enforced at this entry point for all UserTrigger values.
        // Cascade transitions bypass this check via cascadeTransition().
        val ownershipResult = checkOwnershipForTransition(item, actorClaim, verification, degradedModePolicy)
        when (ownershipResult) {
            is OwnershipCheckResult.Allowed -> {} // proceed
            is OwnershipCheckResult.Rejected ->
                return EntryPointTransitionResult(
                    success = false,
                    error = ownershipResult.error
                )
            is OwnershipCheckResult.PolicyRejected ->
                return EntryPointTransitionResult(
                    success = false,
                    error = ownershipResult.reason
                )
        }

        val triggerStr = trigger.triggerString
        val resolution = resolveTransition(item, triggerStr, hasReviewPhase)
        if (!resolution.success || resolution.targetRole == null) {
            return EntryPointTransitionResult(
                success = false,
                error = resolution.error ?: "Failed to resolve transition"
            )
        }
        val targetRole = resolution.targetRole
        val validation = validateTransition(item, targetRole, dependencyRepository, workItemRepository)
        if (!validation.valid) {
            return EntryPointTransitionResult(
                success = false,
                error = validation.error ?: "Transition validation failed"
            )
        }
        val effectiveLabel = resolution.statusLabel ?: statusLabel
        val applyResult =
            applyTransition(
                item,
                targetRole,
                triggerStr,
                summary,
                effectiveLabel,
                workItemRepository,
                roleTransitionRepository,
                actorClaim = actorClaim,
                verification = verification
            )
        return if (applyResult.success) {
            EntryPointTransitionResult(
                success = true,
                item = applyResult.item,
                previousRole = applyResult.previousRole,
                newRole = applyResult.newRole,
                trigger = triggerStr
            )
        } else {
            EntryPointTransitionResult(
                success = false,
                error = applyResult.error
            )
        }
    }

    /**
     * Internal entry point for system-initiated cascade transitions.
     *
     * This method is intentionally marked [internal] so that it is visible only within
     * the same Gradle module. The public `advance_item` MCP tool layer, which lives in
     * the same module but accepts only [UserTrigger] values, cannot invoke this method
     * via any public-API path — the Kotlin visibility modifier is the enforcement
     * mechanism, not string parsing.
     *
     * Cascade transitions bypass ownership checks: they are initiated by the system
     * (e.g., completing the last child auto-completes the parent) and no user claim
     * is involved.
     *
     * @param item The parent work item to cascade-transition.
     * @param targetRole The role that the cascade should advance the item to.
     * @param reason Human-readable description of why the cascade was triggered.
     * @param statusLabel Optional display label for the cascaded item (e.g., "done").
     *   Callers should resolve this from the [io.github.jpicklyk.mcptask.current.application.service.StatusLabelService]
     *   using the "cascade" key before invoking this method.
     * @param workItemRepository Repository for persisting item updates.
     * @param roleTransitionRepository Repository for recording the audit trail.
     */
    internal suspend fun cascadeTransition(
        item: WorkItem,
        targetRole: Role,
        reason: String,
        workItemRepository: WorkItemRepository,
        roleTransitionRepository: RoleTransitionRepository,
        statusLabel: String? = null
    ): EntryPointTransitionResult {
        val applyResult =
            applyTransition(
                item,
                targetRole,
                "cascade",
                reason,
                statusLabel,
                workItemRepository,
                roleTransitionRepository,
                actorClaim = null,
                verification = null
            )
        return if (applyResult.success) {
            EntryPointTransitionResult(
                success = true,
                item = applyResult.item,
                previousRole = applyResult.previousRole,
                newRole = applyResult.newRole,
                trigger = "cascade"
            )
        } else {
            EntryPointTransitionResult(
                success = false,
                error = applyResult.error
            )
        }
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
    fun resolveTransition(
        currentRole: Role,
        trigger: String
    ): TransitionResolution =
        when (trigger.lowercase()) {
            "start" -> resolveStart(currentRole)
            "complete" -> resolveComplete(currentRole)
            "block", "hold" -> resolveBlock(currentRole)
            "resume" ->
                TransitionResolution(
                    success = false,
                    error =
                        "Cannot resolve 'resume' without full WorkItem context (previousRole needed). " +
                            "Use resolveTransition(item, trigger) instead."
                )
            "cancel" -> resolveCancel(currentRole)
            "reopen" -> resolveReopen(currentRole)
            else ->
                TransitionResolution(
                    success = false,
                    error = "Unknown trigger: '$trigger'. Valid triggers: ${USER_TRIGGERS.joinToString()}"
                )
        }

    /**
     * Resolve a trigger to a target role with full WorkItem context.
     * Required for "resume" which restores the item's [WorkItem.previousRole].
     *
     * @param hasReviewPhase When false and the item is in WORK role, the "start" trigger
     *   advances directly to TERMINAL instead of REVIEW. This allows schema-driven
     *   workflows to skip the REVIEW phase when no review notes are defined.
     */
    fun resolveTransition(
        item: WorkItem,
        trigger: String,
        hasReviewPhase: Boolean = true
    ): TransitionResolution =
        when (trigger.lowercase()) {
            "start" -> resolveStart(item.role, hasReviewPhase)
            "complete" -> resolveComplete(item.role)
            "block", "hold" -> resolveBlock(item.role)
            "resume" -> resolveResume(item)
            "cancel" -> resolveCancel(item.role)
            "reopen" -> resolveReopen(item.role)
            else ->
                TransitionResolution(
                    success = false,
                    error = "Unknown trigger: '$trigger'. Valid triggers: ${USER_TRIGGERS.joinToString()}"
                )
        }

    private fun resolveStart(
        currentRole: Role,
        hasReviewPhase: Boolean = true
    ): TransitionResolution =
        when (currentRole) {
            Role.QUEUE -> TransitionResolution(success = true, targetRole = Role.WORK)
            Role.WORK ->
                if (hasReviewPhase) {
                    TransitionResolution(success = true, targetRole = Role.REVIEW)
                } else {
                    TransitionResolution(success = true, targetRole = Role.TERMINAL)
                }
            Role.REVIEW -> TransitionResolution(success = true, targetRole = Role.TERMINAL)
            Role.TERMINAL ->
                TransitionResolution(
                    success = false,
                    error = "Cannot start: item is already terminal"
                )
            Role.BLOCKED ->
                TransitionResolution(
                    success = false,
                    error = "Cannot start: item is blocked. Use 'resume' trigger first"
                )
        }

    private fun resolveComplete(currentRole: Role): TransitionResolution =
        when (currentRole) {
            Role.TERMINAL ->
                TransitionResolution(
                    success = false,
                    error = "Cannot complete: item is already terminal"
                )
            Role.BLOCKED ->
                TransitionResolution(
                    success = false,
                    error = "Cannot complete: item is blocked. Use 'resume' trigger first"
                )
            else -> TransitionResolution(success = true, targetRole = Role.TERMINAL)
        }

    private fun resolveBlock(currentRole: Role): TransitionResolution =
        when (currentRole) {
            Role.BLOCKED ->
                TransitionResolution(
                    success = false,
                    error = "Cannot block: item is already blocked"
                )
            Role.TERMINAL ->
                TransitionResolution(
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
        val restoreRole =
            item.previousRole
                ?: return TransitionResolution(
                    success = false,
                    error = "Cannot resume: item is blocked but has no previousRole to restore"
                )
        return TransitionResolution(success = true, targetRole = restoreRole)
    }

    private fun resolveCancel(currentRole: Role): TransitionResolution =
        when (currentRole) {
            Role.TERMINAL ->
                TransitionResolution(
                    success = false,
                    error = "Cannot cancel: item is already terminal"
                )
            else -> TransitionResolution(success = true, targetRole = Role.TERMINAL, statusLabel = "cancelled")
        }

    private fun resolveReopen(currentRole: Role): TransitionResolution =
        when (currentRole) {
            Role.TERMINAL -> TransitionResolution(success = true, targetRole = Role.QUEUE, statusLabel = null)
            else ->
                TransitionResolution(
                    success = false,
                    error = "Cannot reopen: item is not terminal (current role: ${currentRole.name.lowercase()})"
                )
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

        // Reopen transitions bypass the terminal guard (backward transition)
        if (item.role == Role.TERMINAL && targetRole == Role.QUEUE) {
            return TransitionValidation(valid = true)
        }

        // Terminal items cannot transition further
        if (item.role == Role.TERMINAL) {
            return TransitionValidation(
                valid = false,
                error = "Item is already terminal and cannot transition"
            )
        }

        // For forward progressions, check blocking dependencies from both directions:
        // 1. Incoming BLOCKS deps (dep.toItemId == item.id, blocker is dep.fromItemId)
        // 2. Outgoing IS_BLOCKED_BY deps (dep.fromItemId == item.id, blocker is dep.toItemId)
        if (isForwardProgression(item.role, targetRole)) {
            val blockers = mutableListOf<BlockerInfo>()

            // Check incoming deps for BLOCKS edges
            val incomingDeps = dependencyRepository.findByToItemId(item.id)
            for (dep in incomingDeps) {
                if (dep.type == DependencyType.RELATES_TO) continue
                if (dep.type == DependencyType.IS_BLOCKED_BY) continue // handled below via outgoing

                val threshold = dep.effectiveUnblockRole() ?: continue
                val thresholdRole = Role.fromString(threshold) ?: continue
                val blockerItemId = dep.fromItemId

                val blockerResult = workItemRepository.getById(blockerItemId)
                val blockerItem =
                    when (blockerResult) {
                        is Result.Success -> blockerResult.data
                        is Result.Error -> {
                            blockers.add(
                                BlockerInfo(
                                    itemId = item.id,
                                    fromItemId = blockerItemId,
                                    currentRole = Role.QUEUE,
                                    requiredRole = threshold
                                )
                            )
                            continue
                        }
                    }

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

            // Check outgoing deps for IS_BLOCKED_BY edges
            val outgoingDeps = dependencyRepository.findByFromItemId(item.id)
            for (dep in outgoingDeps) {
                if (dep.type != DependencyType.IS_BLOCKED_BY) continue

                val threshold = dep.effectiveUnblockRole() ?: continue
                val thresholdRole = Role.fromString(threshold) ?: continue
                val blockerItemId = dep.toItemId

                val blockerResult = workItemRepository.getById(blockerItemId)
                val blockerItem =
                    when (blockerResult) {
                        is Result.Success -> blockerResult.data
                        is Result.Error -> {
                            blockers.add(
                                BlockerInfo(
                                    itemId = item.id,
                                    fromItemId = blockerItemId,
                                    currentRole = Role.QUEUE,
                                    requiredRole = threshold
                                )
                            )
                            continue
                        }
                    }

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
    internal fun isForwardProgression(
        from: Role,
        to: Role
    ): Boolean {
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
        roleTransitionRepository: RoleTransitionRepository,
        actorClaim: ActorClaim? = null,
        verification: VerificationResult? = null
    ): TransitionApplyResult {
        val previousRole = item.role

        // Build the updated item via the update builder
        val updatedItem =
            item.update { current ->
                current.copy(
                    role = targetRole,
                    previousRole =
                        when {
                            // When entering BLOCKED, save the current role for later resume
                            targetRole == Role.BLOCKED -> previousRole
                            // When leaving BLOCKED (resume), clear previousRole
                            previousRole == Role.BLOCKED -> null
                            // Otherwise preserve existing previousRole
                            else -> current.previousRole
                        },
                    statusLabel =
                        when {
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
                val transition =
                    RoleTransition(
                        itemId = item.id,
                        fromRole = previousRole.name.lowercase(),
                        toRole = targetRole.name.lowercase(),
                        fromStatusLabel = item.statusLabel,
                        toStatusLabel = statusLabel,
                        trigger = trigger,
                        summary = summary,
                        actorClaim = actorClaim,
                        verification = verification
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
