package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger.Cascade
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger.User
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Role.BLOCKED
import io.github.jpicklyk.mcptask.current.domain.model.Role.QUEUE
import io.github.jpicklyk.mcptask.current.domain.model.Role.REVIEW
import io.github.jpicklyk.mcptask.current.domain.model.Role.TERMINAL
import io.github.jpicklyk.mcptask.current.domain.model.Role.WORK

/**
 * The lifecycle state machine as data. The two maps below ARE the spec: a missing user cell is an
 * invalid transition; a missing cascade cell, or any cascade under a non-AUTO lifecycle, is not
 * applicable. Structural triggers are not part of the table.
 */
object TransitionTable {
    /** A static cell, independent of the item's saved previous role. */
    sealed interface Cell {
        data class To(
            val role: Role
        ) : Cell

        /** Return to the role saved when the item was blocked. */
        data object ToPrevious : Cell

        data object Invalid : Cell

        data object NotApplicable : Cell
    }

    /** A cell resolved against the item's saved previous role. */
    sealed interface Resolution {
        data class To(
            val role: Role
        ) : Resolution

        /** [allowed] lists the user triggers valid from the same role, in [User] declaration order. */
        data class Invalid(
            val allowed: List<User>
        ) : Resolution

        data object NotApplicable : Resolution
    }

    /** Table targets. [AfterWork] is REVIEW when the schema has a review phase, else TERMINAL. */
    private sealed interface Target {
        data class Fixed(
            val role: Role
        ) : Target

        data object Previous : Target

        data object AfterWork : Target
    }

    private fun to(role: Role): Target = Target.Fixed(role)

    /** User triggers. Lifecycle-independent. */
    private val USER: Map<Role, Map<User, Target>> =
        mapOf(
            QUEUE to
                mapOf(
                    User.START to to(WORK),
                    User.COMPLETE to to(TERMINAL),
                    User.BLOCK to to(BLOCKED),
                    User.HOLD to to(BLOCKED),
                    User.CANCEL to to(TERMINAL)
                ),
            WORK to
                mapOf(
                    User.START to Target.AfterWork,
                    User.COMPLETE to to(TERMINAL),
                    User.BLOCK to to(BLOCKED),
                    User.HOLD to to(BLOCKED),
                    User.CANCEL to to(TERMINAL)
                ),
            REVIEW to
                mapOf(
                    User.START to to(TERMINAL),
                    User.COMPLETE to to(TERMINAL),
                    User.BLOCK to to(BLOCKED),
                    User.HOLD to to(BLOCKED),
                    User.CANCEL to to(TERMINAL)
                ),
            BLOCKED to
                mapOf(
                    User.RESUME to Target.Previous,
                    User.CANCEL to to(TERMINAL)
                ),
            TERMINAL to
                mapOf(
                    User.REOPEN to to(QUEUE)
                )
        )

    private enum class CascadeKind { COMPLETE, START, REOPEN }

    /** Cascade triggers. Apply only under [LifecycleMode.AUTO]. */
    private val CASCADE: Map<CascadeKind, Map<Role, Role>> =
        mapOf(
            CascadeKind.COMPLETE to
                mapOf(
                    QUEUE to TERMINAL,
                    WORK to TERMINAL,
                    REVIEW to TERMINAL,
                    BLOCKED to TERMINAL
                ),
            CascadeKind.START to mapOf(QUEUE to WORK),
            CascadeKind.REOPEN to mapOf(TERMINAL to WORK)
        )

    /** Roles that `resume` may return to. */
    private val RESUMABLE: Set<Role> = setOf(QUEUE, WORK, REVIEW)

    private fun kindOf(trigger: Cascade): CascadeKind =
        when (trigger) {
            is Cascade.Complete -> CascadeKind.COMPLETE
            Cascade.Start -> CascadeKind.START
            Cascade.Reopen -> CascadeKind.REOPEN
        }

    /**
     * The static cell for (role, trigger). [trigger] must be a [User] or [Cascade] trigger.
     *
     * @throws IllegalArgumentException for a [Trigger.Structural] trigger.
     */
    fun cell(
        role: Role,
        trigger: Trigger,
        facts: SchemaFacts
    ): Cell =
        when (trigger) {
            is User -> {
                when (val target = USER.getValue(role)[trigger]) {
                    null -> Cell.Invalid
                    is Target.Fixed -> Cell.To(target.role)
                    Target.Previous -> Cell.ToPrevious
                    Target.AfterWork -> Cell.To(if (facts.hasReviewPhase) REVIEW else TERMINAL)
                }
            }
            is Cascade -> {
                if (facts.lifecycle != LifecycleMode.AUTO) {
                    Cell.NotApplicable
                } else {
                    CASCADE.getValue(kindOf(trigger))[role]?.let { Cell.To(it) } ?: Cell.NotApplicable
                }
            }
            is Trigger.Structural -> throw IllegalArgumentException("Structural trigger ${trigger.wire} is not in the transition table")
        }

    /**
     * The cell resolved for an item whose saved previous role is [previousRole]. `resume` from
     * BLOCKED resolves to [previousRole] when it is QUEUE, WORK or REVIEW, else it is invalid.
     *
     * @throws IllegalArgumentException for a [Trigger.Structural] trigger.
     */
    fun resolve(
        role: Role,
        trigger: Trigger,
        facts: SchemaFacts,
        previousRole: Role?
    ): Resolution =
        when (val c = cell(role, trigger, facts)) {
            is Cell.To -> Resolution.To(c.role)
            Cell.ToPrevious ->
                if (previousRole != null && previousRole in RESUMABLE) {
                    Resolution.To(previousRole)
                } else {
                    Resolution.Invalid(allowedUserTriggers(role, facts, previousRole))
                }
            Cell.Invalid -> Resolution.Invalid(allowedUserTriggers(role, facts, previousRole))
            Cell.NotApplicable -> Resolution.NotApplicable
        }

    /** The user triggers that resolve to a target from [role], in [User] declaration order. */
    fun allowedUserTriggers(
        role: Role,
        facts: SchemaFacts,
        previousRole: Role?
    ): List<User> =
        User.entries.filter { trigger ->
            when (cell(role, trigger, facts)) {
                is Cell.To -> true
                Cell.ToPrevious -> previousRole != null && previousRole in RESUMABLE
                else -> false
            }
        }
}
