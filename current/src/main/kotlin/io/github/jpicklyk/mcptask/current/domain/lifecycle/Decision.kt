package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.graph.UnsatisfiedBlocker
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/** The gate that rejected a transition. */
enum class GateId { OWNERSHIP, TABLE, HOLD, DEPENDENCY, NOTE, LEASE }

/** Why a cascade does not apply (cascades only; never an error). */
enum class NotApplicableReason { LIFECYCLE, ROLE, NO_CHILDREN, CHILDREN_ACTIVE }

/** A parent transition the caller must evaluate after applying an [Decision.Allow]. */
sealed interface FollowUp {
    val parentId: UUID

    /** Re-evaluate [Trigger.Cascade.Complete] on the parent. */
    data class TerminalCascade(
        override val parentId: UUID,
        val cancelOrigin: Boolean
    ) : FollowUp

    /** Evaluate [Trigger.Cascade.Start] on the parent. */
    data class StartCascade(
        override val parentId: UUID
    ) : FollowUp

    /** Evaluate [Trigger.Cascade.Reopen] on the parent. */
    data class ReopenCascade(
        override val parentId: UUID
    ) : FollowUp
}

/** Structured context of a [Decision.Reject], beyond its [DomainError]. */
sealed interface RejectContext {
    data object None : RejectContext

    data class Dependency(
        val unsatisfied: List<UnsatisfiedBlocker>
    ) : RejectContext

    /** [missing] are unfilled required notes (may be empty for an independence-only block). */
    data class Notes(
        val missing: List<RequiredNote>,
        val violations: List<IndependenceViolation>?
    ) : RejectContext

    data class Lease(
        val contended: List<String>,
        val retryAfterMs: Long?
    ) : RejectContext
}

/** The outcome of [TransitionPolicy.evaluate]. */
sealed interface Decision {
    /**
     * The transition may be applied.
     *
     * @property violations the independence findings evaluated by the note gate (current-phase or
     *   all-phase list), or null when the note gate was skipped or nothing was declared.
     * @property acquireLeases exclusive resource keys to acquire on apply (entry into WORK only).
     * @property followUps parent cascades to evaluate after apply.
     */
    data class Allow(
        val from: Role,
        val target: Role,
        val violations: List<IndependenceViolation>?,
        val acquireLeases: List<String>,
        val followUps: List<FollowUp>
    ) : Decision

    data class Reject(
        val gate: GateId,
        val error: DomainError,
        val context: RejectContext = RejectContext.None
    ) : Decision

    /** Cascades only: the cascade does not apply; nothing happens. */
    data class NotApplicable(
        val reason: NotApplicableReason
    ) : Decision
}
