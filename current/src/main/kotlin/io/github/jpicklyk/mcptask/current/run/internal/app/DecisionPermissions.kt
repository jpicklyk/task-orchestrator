package io.github.jpicklyk.mcptask.current.run.internal.app

import io.github.jpicklyk.mcptask.current.lifecycle.DecisionRefs
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequest
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.call.ScopedIds
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.run.DecisionCaller
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.internal.wire.RunPermissions

/**
 * The one place a [DecisionCaller] is built from a call: who is acting, as the answerer matrix (plan section 3.3) sees
 * it. Kind and proof come from the transport credential only (never from a request field); `director` is the director
 * profile without a seat token ([RunPermissions.isDirector]); the seat, run and assignment come from the call's seat
 * token.
 */
internal object DecisionCallers {
    suspend fun of(ctx: CallContext): DecisionCaller {
        val credential = ctx.principal.principal
        val seat = ctx.seat
        return DecisionCaller(
            principalId = RunPermissions.callerId(ctx),
            kind = credential?.kind,
            proofStatus = credential?.proofStatus,
            director = RunPermissions.isDirector(ctx),
            seat = seat?.seat,
            runId = seat?.runId,
            assignmentId = seat?.assignmentId,
            provenance = ctx.provenance()
        )
    }
}

/** Resolving and loading a decision request under the call scope (the run rule of [RunsSupport], for decisions). */
internal object DecisionLoader {
    /**
     * Resolves and loads the decision [ref], then checks the call scope against its root BEFORE any answerer or raiser
     * predicate runs: a decision out of scope is `forbidden`, never an existence oracle, and under a restricted scope an
     * unknown decision is `forbidden` too.
     */
    suspend fun load(
        stores: RunStoreProvider,
        ctx: CallContext,
        ref: IdRef
    ): Outcome<DecisionRequest> {
        val id =
            when (val r = DecisionRefs.resolveId(stores.decisionRequestStore(), stores.workItemRepository(), ctx.scope, ref)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        val decision =
            stores.decisionRequestStore().getById(id)
                ?: return Outcome.Err(if (ctx.scope.isUnrestricted) DecisionRefs.notFound(id.toString()) else ScopedIds.denied(ctx.scope))
        return when (val guard = ScopeGuard.check(ctx.scope, decision.rootId, stores.workItemRepository())) {
            is Outcome.Err -> guard
            is Outcome.Ok -> Outcome.Ok(decision)
        }
    }
}
