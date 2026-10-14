package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.AUDIT_ID_PREFIX
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile

/**
 * Who may do what to a run (plan section 3.2), one predicate per row. Every predicate runs AFTER the run's root passed
 * the scope check, so a refusal here never reveals whether a run outside the callers scope exists.
 *
 * - director: the call was served by the director profile (a profile, not a principal attribute).
 * - human: the credential is a human one.
 * - coordinator: until the coordinator token exists (R22), the caller that opened the run: the caller's principal id
 *   equals `run.openedBy` AND its credential kind equals `run.openedByKind`. Every call carries a transport principal
 *   (R14: a principal-less call is refused `unauthenticated` before any handler runs, and no request body names its
 *   own actor); the local principal (stdio, `/mcp` local mode) is id `local`, kind `agent`. A call principal without
 *   a credential exists only when a test builds one by hand; it is never the same caller as a credential (it cannot
 *   take over the `api:` id space), and it is the opener only of a run recorded with kind `agent`.
 */
internal object RunPermissions {
    const val CLOSE_REQUIRED = "coordinator,director,human"
    const val PAUSE_REQUIRED = "director,human"
    const val UPDATE_REQUIRED = "coordinator"

    private const val LIFTED_KIND = "agent"

    /** The audited id of the caller: the credential `api:<id>`, or `local` for the local principal. */
    fun callerId(ctx: CallContext): String = ctx.principal.claim.id

    /** The `opened_by_kind` to record: the credential kind (`agent` for the local principal). */
    fun openerKind(ctx: CallContext): String =
        ctx.principal.principal
            ?.kind
            ?.wire ?: LIFTED_KIND

    fun isDirector(ctx: CallContext): Boolean = ctx.profile == ToolProfile.DIRECTOR.wire

    fun isHuman(ctx: CallContext): Boolean = ctx.principal.principal?.kind == PrincipalKind.HUMAN

    fun isCoordinator(
        ctx: CallContext,
        run: Run
    ): Boolean {
        val id = callerId(ctx)
        if (id != run.openedBy) return false
        val credential = ctx.principal.principal
        return if (credential != null) {
            run.openedByKind == credential.kind.wire
        } else {
            !id.startsWith(AUDIT_ID_PREFIX) && run.openedByKind == LIFTED_KIND
        }
    }

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

    /** The refusal naming the principal kinds [required] (comma-separated, sorted) that could have done the call. */
    fun refuse(required: String): DomainError = ScopeGuard.forbidden(ForbiddenScope.PRINCIPAL_KIND, required)
}
