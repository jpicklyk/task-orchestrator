package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile

/**
 * Who may do what to a run (plan section 3.2), one predicate per row. Every predicate runs AFTER the run's root passed
 * the scope check, so a refusal here never reveals whether a run outside the callers scope exists.
 *
 * - director: the call was served by the director profile (a profile, not a principal attribute) and carries no seat
 *   token.
 * - human: the credential is a human one and the call carries no seat token (a seat is always an agent).
 * - coordinator: the call carries the run's coordinator token (R22): its seat is the `coordinator` pseudo-seat of THIS
 *   run. The token is the only coordinator credential; the opening principal is not the coordinator by identity (a seat
 *   sharing the opener's stdio principal must not be one).
 * - seat: the call carries a member-seat token (`end_seat` ends the calling seat's own assignment).
 */
internal object RunPermissions {
    const val CLOSE_REQUIRED = "coordinator,director,human"
    const val PAUSE_REQUIRED = "director,human"
    const val UPDATE_REQUIRED = "coordinator"
    const val ASSIGN_REQUIRED = "coordinator"
    const val END_SEAT_REQUIRED = "seat"

    private const val LIFTED_KIND = "agent"

    /** The audited id of the caller: the credential `api:<id>`, or `local` for the local principal. */
    fun callerId(ctx: CallContext): String = ctx.principal.claim.id

    /** The `opened_by_kind` to record: the credential kind (`agent` for the local principal). */
    fun openerKind(ctx: CallContext): String =
        ctx.principal.principal
            ?.kind
            ?.wire ?: LIFTED_KIND

    fun isDirector(ctx: CallContext): Boolean = ctx.seat == null && ctx.profile == ToolProfile.DIRECTOR.wire

    fun isHuman(ctx: CallContext): Boolean = ctx.seat == null && ctx.principal.principal?.kind == PrincipalKind.HUMAN

    /** True when the call carries [run]'s coordinator token. */
    fun isCoordinator(
        ctx: CallContext,
        run: Run
    ): Boolean {
        val seat = ctx.seat ?: return false
        return seat.seat == RunSeat.COORDINATOR && seat.runId == run.id
    }

    /** True when the call carries a member-seat token (any run). */
    fun isMemberSeat(ctx: CallContext): Boolean = ctx.seat?.let { it.seat != RunSeat.COORDINATOR && it.itemId != null } == true

    /** close, any reason. */
    fun mayClose(
        ctx: CallContext,
        run: Run
    ): Boolean = isCoordinator(ctx, run) || isDirector(ctx) || isHuman(ctx)

    /** pause and resume. */
    fun mayPauseOrResume(ctx: CallContext): Boolean = isDirector(ctx) || isHuman(ctx)

    /** update. */
    fun mayUpdate(
        ctx: CallContext,
        run: Run
    ): Boolean = isCoordinator(ctx, run)

    /** assign. */
    fun mayAssign(
        ctx: CallContext,
        run: Run
    ): Boolean = isCoordinator(ctx, run)

    /** The refusal naming the principal kinds [required] (comma-separated, sorted) that could have done the call. */
    fun refuse(required: String): DomainError = ScopeGuard.forbidden(ForbiddenScope.PRINCIPAL_KIND, required)
}
