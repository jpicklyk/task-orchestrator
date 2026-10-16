package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.error.Blocker
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.GateViolation
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
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import java.util.UUID

/** What a [TransitionGate] sees: the snapshot, the trigger, and the table resolution for it. */
data class GateInput(
    val snapshot: TransitionSnapshot,
    val trigger: Trigger,
    val resolution: TransitionTable.Resolution
) {
    /** The item's current role; null for a lifecycle-free item. */
    val from: Role? get() = snapshot.item.role

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

/** Wire form of a role that may be null: a null (lifecycle-free) role is "none". */
internal fun Role?.wireOrNone(): String = this?.wire() ?: NONE_ROLE_WIRE

/** The `fromRole` reported for a lifecycle-free (null-role) item. */
internal const val NONE_ROLE_WIRE: String = "none"

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
    /** User triggers only: a live claim held by a holder pair other than the caller's rejects. */
    val OWNERSHIP: TransitionGate =
        TransitionGate { input ->
            val ownership = input.snapshot.ownership
            val holder = ownership.activeHolder
            val callerHolds = ownership.callerId == holder && ownership.callerAssignment == ownership.activeHolderAssignment
            if (input.trigger !is User || !ownership.enforced || holder == null || callerHolds) {
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

    /**
     * Seat enforcement (R23), user triggers only. Applies only when the caller is known ([SeatFacts.enforced]), the item
     * is a member of a not-closed run ([SeatFacts.openRunId]) and its pinned schema is seat-aware; a human caller is
     * exempt. Then the coordinator token of THIS run may send any trigger the table allows, a member-seat token of THIS
     * run bound to THIS item may `start` only into the phase its seat enters and send any other trigger only when its
     * seat's effective `transitions:` list it, and every other caller (no token, another run's token, a token for another
     * item, a seat the schema does not declare) is refused with `seat_forbidden`.
     */
    val SEAT: TransitionGate =
        TransitionGate { input ->
            val facts = input.snapshot.seat
            val trigger = input.trigger
            when {
                trigger !is User -> null
                !facts.enforced || facts.openRunId == null || facts.seats.isEmpty() || facts.caller.human -> null
                seatAllows(facts, trigger, input.snapshot.item.id, input.target) -> null
                else -> seatForbidden(facts, trigger, input.snapshot.item.id, input.target)
            }
        }

    /**
     * The table: invalid user cells reject; inapplicable cascade cells stop with NotApplicable. A
     * lifecycle-free item rejects with `fromRole` "none" and no allowed triggers, and its cascades are
     * not applicable for [NotApplicableReason.LIFECYCLE].
     */
    val TABLE: TransitionGate =
        TransitionGate { input ->
            when (val r = input.resolution) {
                is TransitionTable.Resolution.To -> null
                is TransitionTable.Resolution.Invalid ->
                    invalidTransition(GateId.TABLE, input.snapshot.item.id, input.from, input.trigger.wire, r.allowed.map { it.wire })
                TransitionTable.Resolution.NotApplicable ->
                    Decision.NotApplicable(
                        if (input.from == null || input.snapshot.schema.lifecycle != LifecycleMode.AUTO) {
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

    /**
     * A Complete cascade never moves a parent out of REVIEW: the parent leaves review only by an explicit advance (a
     * reviewer's verdict). Whatever the origin, cancel-origin included. Runs after WARRANT, so an unwarranted cascade
     * stays silent; carries no missing notes.
     */
    val REVIEW_PENDING: TransitionGate =
        TransitionGate { input ->
            if (input.trigger is Cascade.Complete && input.from == Role.REVIEW) {
                val id = input.snapshot.item.id
                Decision.Reject(
                    GateId.REVIEW_PENDING,
                    DomainError(
                        code = ErrorCode.GATE_BLOCKED,
                        message = "Item $id is in review; a cascade does not complete it.",
                        detail =
                            ErrorDetail.GateBlocked(
                                id,
                                Role.REVIEW.wire(),
                                emptyList(),
                                listOf(GateViolation("review-pending", "a cascade does not complete an item in review"))
                            ),
                        fixArgs = mapOf("itemId" to id.toString())
                    )
                )
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
                val detailMissing = eval.missing.map { MissingNote(it.key, it.role.wire(), it.seat) }
                val blocking =
                    if (input.snapshot.independence?.mode == IndependenceMode.REJECT) {
                        eval.violations
                            .orEmpty()
                            .filter { !it.waived }
                            .map { gateViolation(it) }
                    } else {
                        emptyList()
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
                        detail = ErrorDetail.GateBlocked(item.id, item.role.wireOrNone(), detailMissing, blocking.ifEmpty { null }),
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
                                lease.heldElsewhere.map { key ->
                                    // Only exclusive requirements are ever held elsewhere; the loader records each key's mode.
                                    ResourceRef(key, (lease.heldModes[key] ?: ResourceMode.EXCLUSIVE).wire)
                                },
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

    /**
     * The `independence` gate violation of a blocking finding: `<key>: <constraint> conflicts with seat <conflictingSeat>`
     * (the seat part is omitted when the finding names none). Seats only, never an actor.
     */
    private fun gateViolation(v: IndependenceViolation): GateViolation {
        val seat = v.conflictingSeat?.let { " conflicts with seat $it" }.orEmpty()
        return GateViolation("independence", "${v.key}: ${v.constraint.toJsonString()}$seat")
    }
}

/** Whether [facts]' caller may send [trigger] to [itemId] (resolved [target], null when the table resolved none). */
private fun seatAllows(
    facts: SeatFacts,
    trigger: User,
    itemId: UUID,
    target: Role?
): Boolean {
    val caller = facts.caller
    val seat = caller.seat ?: return false
    // A token of another run carries no authority here, whatever its seat.
    if (caller.runId == null || caller.runId != facts.openRunId) return false
    if (seat == RunSeat.COORDINATOR) return true
    // A member-seat token acts only on the item it is assigned to.
    if (caller.itemId != itemId) return false
    // Fail closed on a seat name the pinned schema does not declare.
    val definition = facts.seats.firstOrNull { it.name == seat } ?: return false
    return seatMayRun(definition, trigger, target)
}

/**
 * Whether a cascade into a seat-enforced member may proceed for the write's caller in [facts]: true when the seat gate
 * cannot act (not enforced, no open run, seat-less schema, human caller), else when the caller could send the
 * equivalent user trigger to [itemId] itself. [StandardGates.SEAT] itself ignores cascades; the cascade step applies
 * this predicate to the cascade's parent.
 */
internal fun cascadeSeatAllowed(
    facts: SeatFacts,
    trigger: Cascade,
    itemId: UUID,
    target: Role?
): Boolean {
    if (!facts.enforced || facts.openRunId == null || facts.seats.isEmpty() || facts.caller.human) return true
    val equivalent =
        when (trigger) {
            Cascade.Start -> User.START
            is Cascade.Complete -> User.COMPLETE
            Cascade.Reopen -> User.REOPEN
        }
    return seatAllows(facts, equivalent, itemId, target)
}

/** Whether [definition] may send [trigger] given the resolved [target]: `start` only into the phase it enters. */
private fun seatMayRun(
    definition: SeatDefinition,
    trigger: User,
    target: Role?
): Boolean =
    if (trigger == User.START) {
        definition.enters && target != null && definition.phase == target
    } else {
        trigger.wire in definition.effectiveTransitions
    }

/** The `seat_forbidden` rejection: `allowedSeats` lists the coordinator, then the schema seats that may send [trigger]. */
private fun seatForbidden(
    facts: SeatFacts,
    trigger: User,
    itemId: UUID,
    target: Role?
): Decision.Reject {
    val seat = facts.caller.seat
    val allowedSeats = listOf(RunSeat.COORDINATOR) + facts.seats.filter { seatMayRun(it, trigger, target) }.map { it.name }
    val who = if (seat != null) "seat $seat" else "a caller without a seat"
    return Decision.Reject(
        GateId.SEAT,
        DomainError(
            code = ErrorCode.SEAT_FORBIDDEN,
            message = "Trigger ${trigger.wire} on item $itemId is not allowed for $who.",
            detail = ErrorDetail.SeatForbidden(itemId, seat, trigger.wire, allowedSeats),
            fixArgs = mapOf("itemId" to itemId.toString(), "action" to trigger.wire)
        )
    )
}

internal fun invalidTransition(
    gate: GateId,
    itemId: UUID,
    from: Role?,
    trigger: String,
    allowed: List<String>
): Decision.Reject {
    val fromWire = from.wireOrNone()
    return Decision.Reject(
        gate,
        DomainError(
            code = ErrorCode.INVALID_TRANSITION,
            message = "Trigger $trigger is not valid for item $itemId in role $fromWire.",
            detail = ErrorDetail.InvalidTransition(itemId, fromWire, trigger, allowed),
            fixArgs = mapOf("itemId" to itemId.toString(), "trigger" to trigger, "fromRole" to fromWire)
        )
    )
}

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
        /** OWNERSHIP -> SEAT -> TABLE -> WARRANT -> HOLD -> REVIEW_PENDING -> DEPENDENCY -> NOTE -> LEASE. */
        val DEFAULT_GATES: List<TransitionGate> =
            listOf(
                StandardGates.OWNERSHIP,
                StandardGates.SEAT,
                StandardGates.TABLE,
                StandardGates.WARRANT,
                StandardGates.HOLD,
                StandardGates.REVIEW_PENDING,
                StandardGates.DEPENDENCY,
                StandardGates.NOTE,
                StandardGates.LEASE
            )
    }
}
