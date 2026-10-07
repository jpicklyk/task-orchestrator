package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.error.Blocker
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.MissingNote
import io.github.jpicklyk.mcptask.current.domain.error.ResourceRef
import io.github.jpicklyk.mcptask.current.domain.graph.BlockerEvaluator
import io.github.jpicklyk.mcptask.current.domain.graph.UnsatisfiedBlocker
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger.Cascade
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger.Structural
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger.User
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceMode
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/** What a [TransitionGate] sees: the snapshot, the trigger, and the table resolution for it. */
data class GateInput(
    val snapshot: TransitionSnapshot,
    val trigger: Trigger,
    val resolution: TransitionTable.Resolution
) {
    val from: Role get() = snapshot.item.role

    /** The resolved target, or null when the table did not resolve one. */
    val target: Role? get() = (resolution as? TransitionTable.Resolution.To)?.role
}

/**
 * One ordered gate. Returns null to pass, or a [Decision.Reject] / [Decision.NotApplicable] to stop
 * evaluation (first failure wins). Gates never return [Decision.Allow].
 */
fun interface TransitionGate {
    fun check(input: GateInput): Decision?
}

/** Lowercase wire form of a role. */
internal fun Role.wire(): String = name.lowercase()

/** Which required notes and independence findings a trigger's note gate covers. */
internal enum class NoteScope { CURRENT_PHASE, ALL_PHASES }

/**
 * START and the start cascade check the current phase; COMPLETE and a non-cancel-origin complete
 * cascade check every phase; every other trigger skips the note gate.
 */
internal fun noteScopeOf(trigger: Trigger): NoteScope? =
    when (trigger) {
        User.START -> NoteScope.CURRENT_PHASE
        User.COMPLETE -> NoteScope.ALL_PHASES
        is Cascade.Complete -> if (trigger.cancelOrigin) null else NoteScope.ALL_PHASES
        Cascade.Start -> NoteScope.CURRENT_PHASE
        else -> null
    }

/** The note-gate evaluation; null when the gate is skipped (trigger out of scope or schema-free). */
internal data class NoteEvaluation(
    val missing: List<RequiredNote>,
    val violations: List<IndependenceViolation>?,
    val blocks: Boolean
)

internal fun evaluateNotes(
    snapshot: TransitionSnapshot,
    trigger: Trigger
): NoteEvaluation? {
    val scope = noteScopeOf(trigger) ?: return null
    val required = snapshot.requiredNotes ?: return null
    val inScope =
        when (scope) {
            NoteScope.CURRENT_PHASE -> required.filter { it.role == snapshot.item.role }
            NoteScope.ALL_PHASES -> required
        }
    val missing = inScope.filter { it.key !in snapshot.filledKeys }
    val independence = snapshot.independence
    val violations =
        when (scope) {
            NoteScope.CURRENT_PHASE -> independence?.currentPhase
            NoteScope.ALL_PHASES -> independence?.allPhases
        }
    val independenceBlocks =
        independence?.mode == IndependenceMode.REJECT && violations.orEmpty().any { !it.waived }
    return NoteEvaluation(missing, violations, missing.isNotEmpty() || independenceBlocks)
}

/** The standard gates, in their mandated order (see [TransitionPolicy.DEFAULT_GATES]). */
object StandardGates {
    /** User triggers only: a live claim held by someone other than the caller rejects. */
    val OWNERSHIP: TransitionGate =
        TransitionGate { input ->
            val ownership = input.snapshot.ownership
            val holder = ownership.activeHolder
            if (input.trigger !is User || !ownership.enforced || holder == null || ownership.callerId == holder) {
                null
            } else {
                val id = input.snapshot.item.id
                Decision.Reject(
                    GateId.OWNERSHIP,
                    DomainError(
                        code = ErrorCode.NOT_CLAIM_HOLDER,
                        message = "Item $id is claimed by another actor.",
                        detail = ErrorDetail.NotClaimHolder(id),
                        fixArgs = mapOf("itemId" to id.toString())
                    )
                )
            }
        }

    /** The table: invalid user cells reject; inapplicable cascade cells stop with NotApplicable. */
    val TABLE: TransitionGate =
        TransitionGate { input ->
            when (val r = input.resolution) {
                is TransitionTable.Resolution.To -> null
                is TransitionTable.Resolution.Invalid ->
                    invalidTransition(GateId.TABLE, input.snapshot.item.id, input.from, input.trigger.wire, r.allowed.map { it.wire })
                TransitionTable.Resolution.NotApplicable ->
                    Decision.NotApplicable(
                        if (input.snapshot.schema.lifecycle != LifecycleMode.AUTO) {
                            NotApplicableReason.LIFECYCLE
                        } else {
                            NotApplicableReason.ROLE
                        }
                    )
            }
        }

    /** Complete cascade only: the parent needs at least one child and every child terminal. */
    val WARRANT: TransitionGate =
        TransitionGate { input ->
            val children = input.snapshot.children
            when {
                input.trigger !is Cascade.Complete -> null
                children.total <= 0 -> Decision.NotApplicable(NotApplicableReason.NO_CHILDREN)
                children.terminal < children.total -> Decision.NotApplicable(NotApplicableReason.CHILDREN_ACTIVE)
                else -> null
            }
        }

    /** No cascade moves an item out of BLOCKED. */
    val HOLD: TransitionGate =
        TransitionGate { input ->
            if (input.trigger is Cascade && input.from == Role.BLOCKED) {
                val item = input.snapshot.item
                val allowed = TransitionTable.allowedUserTriggers(Role.BLOCKED, input.snapshot.schema, item.previousRole)
                invalidTransition(GateId.HOLD, item.id, Role.BLOCKED, Cascade.WIRE, allowed.map { it.wire })
            } else {
                null
            }
        }

    /** Entry into WORK, REVIEW or TERMINAL (except by `cancel`) needs every blocker satisfied. */
    val DEPENDENCY: TransitionGate =
        TransitionGate { input ->
            val target = input.target
            if (target == null || target !in DEPENDENCY_TARGETS || input.trigger == User.CANCEL) {
                null
            } else {
                val unsatisfied =
                    input.snapshot.blockers
                        .filter { !BlockerEvaluator.isSatisfied(it.edge, it.blockerRole) }
                        .map { UnsatisfiedBlocker(it.edge.blocker, it.blockerRole, it.edge.unblockAt) }
                if (unsatisfied.isEmpty()) {
                    null
                } else {
                    val id = input.snapshot.item.id
                    Decision.Reject(
                        GateId.DEPENDENCY,
                        DomainError(
                            code = ErrorCode.DEPENDENCY_UNMET,
                            message = "Item $id has ${unsatisfied.size} unsatisfied blocking dependencies.",
                            detail =
                                ErrorDetail.DependencyUnmet(
                                    id,
                                    unsatisfied.map { Blocker(it.blockerId, it.role?.wire() ?: UNKNOWN_ROLE) }
                                ),
                            fixArgs = mapOf("itemId" to id.toString())
                        ),
                        RejectContext.Dependency(unsatisfied)
                    )
                }
            }
        }

    /** Required notes and independence attestation, scoped by [noteScopeOf]. */
    val NOTE: TransitionGate =
        TransitionGate { input ->
            val eval = evaluateNotes(input.snapshot, input.trigger)
            if (eval == null || !eval.blocks) {
                null
            } else {
                val item = input.snapshot.item
                val detailMissing =
                    if (eval.missing.isNotEmpty()) {
                        eval.missing.map { MissingNote(it.key, it.role.wire(), it.seat) }
                    } else {
                        independenceMissing(input.snapshot, eval.violations.orEmpty())
                    }
                Decision.Reject(
                    GateId.NOTE,
                    DomainError(
                        code = ErrorCode.GATE_BLOCKED,
                        message =
                            if (eval.missing.isNotEmpty()) {
                                "Item ${item.id} is missing ${eval.missing.size} required notes."
                            } else {
                                "Item ${item.id} has blocking independence violations."
                            },
                        detail = ErrorDetail.GateBlocked(item.id, item.role.wire(), detailMissing),
                        fixArgs = mapOf("itemId" to item.id.toString())
                    ),
                    RejectContext.Notes(eval.missing, eval.violations)
                )
            }
        }

    /** Entry into WORK: an exclusive resource held by another item rejects (transient). */
    val LEASE: TransitionGate =
        TransitionGate { input ->
            val lease = input.snapshot.lease
            if (input.target != Role.WORK || !lease.enforced || lease.exclusiveKeys.isEmpty() || lease.heldElsewhere.isEmpty()) {
                null
            } else {
                val id = input.snapshot.item.id
                Decision.Reject(
                    GateId.LEASE,
                    DomainError(
                        code = ErrorCode.RESOURCE_UNAVAILABLE,
                        message = "Item $id needs ${lease.heldElsewhere.size} resources held by other items.",
                        detail =
                            ErrorDetail.ResourceUnavailable(
                                id,
                                lease.heldElsewhere.map { ResourceRef(it, EXCLUSIVE) },
                                lease.retryAfterMs
                            ),
                        fixArgs = mapOf("itemId" to id.toString())
                    ),
                    RejectContext.Lease(lease.heldElsewhere, lease.retryAfterMs)
                )
            }
        }

    private val DEPENDENCY_TARGETS: Set<Role> = setOf(Role.WORK, Role.REVIEW, Role.TERMINAL)

    /** Blocker role reported for an unreadable blocker. */
    const val UNKNOWN_ROLE: String = "unknown"

    private const val EXCLUSIVE: String = "exclusive"

    /**
     * An independence-only block: GateBlocked requires a non-empty `missing`, so report the
     * declaring notes of the blocking (non-waived) violations, deduplicated in finding order. The
     * role comes from the required-note list when the key is there, else the item's current role.
     */
    private fun independenceMissing(
        snapshot: TransitionSnapshot,
        violations: List<IndependenceViolation>
    ): List<MissingNote> =
        violations
            .filter { !it.waived }
            .distinctBy { it.key }
            .map { v ->
                val role = snapshot.requiredNotes?.firstOrNull { it.key == v.key }?.role ?: snapshot.item.role
                MissingNote(v.key, role.wire(), v.seat)
            }
}

internal fun invalidTransition(
    gate: GateId,
    itemId: UUID,
    from: Role,
    trigger: String,
    allowed: List<String>
): Decision.Reject =
    Decision.Reject(
        gate,
        DomainError(
            code = ErrorCode.INVALID_TRANSITION,
            message = "Trigger $trigger is not valid for item $itemId in role ${from.wire()}.",
            detail = ErrorDetail.InvalidTransition(itemId, from.wire(), trigger, allowed),
            fixArgs = mapOf("itemId" to itemId.toString(), "trigger" to trigger, "fromRole" to from.wire())
        )
    )

/**
 * The pure lifecycle policy. [evaluate] runs [gates] in order (first failure wins) and, when all
 * pass, returns the [Decision.Allow] with the effects the caller applies. Structural triggers are
 * evaluated by fixed rules and bypass the gate list.
 */
class TransitionPolicy(
    private val gates: List<TransitionGate> = DEFAULT_GATES
) {
    fun evaluate(
        snapshot: TransitionSnapshot,
        trigger: Trigger
    ): Decision {
        if (trigger is Structural) return evaluateStructural(snapshot, trigger)
        val item = snapshot.item
        val resolution = TransitionTable.resolve(item.role, trigger, snapshot.schema, item.previousRole)
        val input = GateInput(snapshot, trigger, resolution)
        for (gate in gates) {
            gate.check(input)?.let { return it }
        }
        val target =
            input.target
                ?: error("Gate list passed a trigger the table did not resolve; include StandardGates.TABLE")
        return Decision.Allow(
            from = item.role,
            target = target,
            violations = evaluateNotes(snapshot, trigger)?.violations,
            acquireLeases = if (target == Role.WORK && snapshot.lease.enforced) snapshot.lease.exclusiveKeys else emptyList(),
            followUps = followUps(item.parentId, trigger, target)
        )
    }

    private fun followUps(
        parentId: UUID?,
        trigger: Trigger,
        target: Role
    ): List<FollowUp> {
        if (parentId == null) return emptyList()
        return when {
            target == Role.TERMINAL -> {
                val cancelOrigin = trigger == User.CANCEL || (trigger is Cascade.Complete && trigger.cancelOrigin)
                listOf(FollowUp.TerminalCascade(parentId, cancelOrigin))
            }
            trigger == User.REOPEN -> listOf(FollowUp.ReopenCascade(parentId))
            trigger is User && target == Role.WORK -> listOf(FollowUp.StartCascade(parentId))
            else -> emptyList()
        }
    }

    private fun evaluateStructural(
        snapshot: TransitionSnapshot,
        trigger: Structural
    ): Decision {
        val item = snapshot.item
        val oldParent = listOfNotNull(item.parentId?.let { FollowUp.TerminalCascade(it, cancelOrigin = false) })
        return when (trigger) {
            is Structural.Create ->
                closedParent(trigger.parent, trigger.wire)
                    ?: Decision.Allow(Role.QUEUE, Role.QUEUE, null, emptyList(), emptyList())
            is Structural.Reparent ->
                closedParent(trigger.newParent, trigger.wire)
                    ?: Decision.Allow(item.role, item.role, null, emptyList(), oldParent)
            Structural.Delete -> Decision.Allow(item.role, item.role, null, emptyList(), oldParent)
        }
    }

    /** A TERMINAL parent under AUTO lifecycle takes no new children; the fix is to reopen it. */
    private fun closedParent(
        parent: ParentFacts?,
        wire: String
    ): Decision.Reject? =
        if (parent != null && parent.role == Role.TERMINAL && parent.lifecycle == LifecycleMode.AUTO) {
            invalidTransition(GateId.TABLE, parent.id, Role.TERMINAL, wire, listOf(User.REOPEN.wire))
        } else {
            null
        }

    companion object {
        /** OWNERSHIP -> TABLE -> WARRANT -> HOLD -> DEPENDENCY -> NOTE -> LEASE. W5 appends SEAT. */
        val DEFAULT_GATES: List<TransitionGate> =
            listOf(
                StandardGates.OWNERSHIP,
                StandardGates.TABLE,
                StandardGates.WARRANT,
                StandardGates.HOLD,
                StandardGates.DEPENDENCY,
                StandardGates.NOTE,
                StandardGates.LEASE
            )
    }
}
