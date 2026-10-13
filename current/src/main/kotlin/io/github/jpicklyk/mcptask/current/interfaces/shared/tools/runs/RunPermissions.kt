package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.AUDIT_ID_PREFIX
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile

/**
 * Who may do what to a run (plan section 3.2), one predicate per row. Every predicate runs AFTER the run's root passed
 * the scope check, so a refusal here never reveals whether a run outside the callers scope exists.
 *
 * - director: the call was served by the director profile (a profile, not a principal attribute).
 * - human: the credential is a human one; a request-body `actor` can never be human.
 * - coordinator: until the coordinator token exists (R22), the caller that opened the run. A lifted body `actor` is
 *   never the same caller as a credential: it cannot take over the `api:` id space of the credentials, and a credential
 *   is the opener only when the run records its kind.
 */
internal object RunPermissions {
    const val CLOSE_REQUIRED = "coordinator,director,human"
    const val PAUSE_REQUIRED = "director,human"
    const val UPDATE_REQUIRED = "coordinator"

    private const val LIFTED_KIND = "agent"

    /** The audited id of the caller: the credential `api:<id>` or the lifted body actor id; null when there is none. */
    fun callerId(ctx: CallContext): String? = ctx.principal?.claim?.id

    /** The `opened_by_kind` to record: the credential kind, `agent` for a lifted body actor, null with no principal. */
    fun openerKind(ctx: CallContext): String? {
        val call = ctx.principal ?: return null
        return call.principal?.kind?.wire ?: LIFTED_KIND
    }

    fun isDirector(ctx: CallContext): Boolean = ctx.profile == ToolProfile.DIRECTOR.wire

    fun isHuman(ctx: CallContext): Boolean = ctx.principal?.principal?.kind == PrincipalKind.HUMAN

    fun isCoordinator(
        ctx: CallContext,
        run: Run
    ): Boolean {
        val call = ctx.principal
        val id = callerId(ctx)
        if (id != run.openedBy) return false
        if (call == null) return true
        val credential = call.principal
        return if (credential != null) {
            run.openedByKind == credential.kind.wire
        } else {
            !id.orEmpty().startsWith(AUDIT_ID_PREFIX) && run.openedByKind == LIFTED_KIND
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
