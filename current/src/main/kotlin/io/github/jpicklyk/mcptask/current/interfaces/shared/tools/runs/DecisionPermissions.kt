package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.decision.DecisionCaller
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import java.util.UUID

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
    private const val UUID_LENGTH = 36
    private const val PREFIX_PROBE = 2
    private const val RESTRICTED_PREFIX_PROBE = 200

    /**
     * Resolves a decision id as the caller wrote it, under [scope]: a full UUID resolves with no read, a hex prefix through
     * the store. Unrestricted scope: no match is `not_found`, two or more `ambiguous_id`. Restricted scope: no match is
     * `forbidden` (never `not_found`), and an ambiguous prefix keeps only the candidates whose root [ScopeGuard] admits.
     */
    suspend fun resolveId(
        stores: RepositoryProvider,
        scope: AccessScope,
        ref: IdRef
    ): Outcome<UUID> {
        if (ref.raw.length == UUID_LENGTH) return Outcome.Ok(UUID.fromString(ref.raw))
        val restricted = !scope.isUnrestricted
        val store = stores.decisionRequestStore()
        var hits = store.findIdsByPrefix(ref.raw, if (restricted) RESTRICTED_PREFIX_PROBE else PREFIX_PROBE)
        if (restricted) {
            hits =
                hits.filter { id ->
                    val decision = store.getById(id)
                    decision != null && ScopeGuard.check(scope, decision.rootId, stores.workItemRepository()) is Outcome.Ok
                }
        }
        return when (hits.size) {
            0 -> Outcome.Err(if (restricted) ScopedIds.denied(scope) else notFound(ref.raw))
            1 -> Outcome.Ok(hits.single())
            else ->
                Outcome.Err(
                    DomainError(
                        code = ErrorCode.AMBIGUOUS_ID,
                        message = "The id ${ref.raw} matches more than one decision.",
                        detail = ErrorDetail.AmbiguousId(ref.raw, hits),
                        fixArgs = mapOf("prefix" to ref.raw)
                    )
                )
        }
    }

    /**
     * Resolves and loads the decision [ref], then checks the call scope against its root BEFORE any answerer or raiser
     * predicate runs: a decision out of scope is `forbidden`, never an existence oracle, and under a restricted scope an
     * unknown decision is `forbidden` too.
     */
    suspend fun load(
        stores: RepositoryProvider,
        ctx: CallContext,
        ref: IdRef
    ): Outcome<DecisionRequest> {
        val id =
            when (val r = resolveId(stores, ctx.scope, ref)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        val decision =
            stores.decisionRequestStore().getById(id)
                ?: return Outcome.Err(if (ctx.scope.isUnrestricted) notFound(id.toString()) else ScopedIds.denied(ctx.scope))
        return when (val guard = ScopeGuard.check(ctx.scope, decision.rootId, stores.workItemRepository())) {
            is Outcome.Err -> guard
            is Outcome.Ok -> Outcome.Ok(decision)
        }
    }

    private fun notFound(raw: String): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "No decision matches $raw.",
            detail = ErrorDetail.NotFound(EntityKind.DECISION, raw),
            fixArgs = mapOf("kind" to "decision", "id" to raw)
        )
}
