package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.application.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Run
import java.util.UUID

/**
 * Run id resolution under an access scope (the `runs_read get` rule), shared by the `runs_*` handlers and the run
 * rollup. Every function reads [RepositoryProvider] stores inside the caller's unit.
 */
object RunRefs {
    private const val UUID_LENGTH = 36
    private const val PREFIX_PROBE = 2
    private const val RESTRICTED_PREFIX_PROBE = 200

    /**
     * Resolves a run id as the caller wrote it, under [scope] (the ScopedIds rule, for runs): a full UUID resolves with
     * no read, a hex prefix through [io.github.jpicklyk.mcptask.current.application.port.RunStore.findIdsByPrefix].
     * Unrestricted scope: no match is `not_found`, two or more `ambiguous_id`. Restricted scope: no match is `forbidden`
     * (never `not_found`), and an ambiguous prefix keeps only the candidates [ScopeGuard] admits (none is `forbidden`, one
     * resolves, two or more is `ambiguous_id` listing only those). Whether a full UUID names a run is the loader question.
     */
    suspend fun resolveRunId(
        stores: RepositoryProvider,
        scope: AccessScope,
        ref: IdRef
    ): Outcome<UUID> {
        if (ref.raw.length == UUID_LENGTH) return Outcome.Ok(UUID.fromString(ref.raw))
        val restricted = !scope.isUnrestricted
        val probe = if (restricted) RESTRICTED_PREFIX_PROBE else PREFIX_PROBE
        var hits = stores.runStore().findIdsByPrefix(ref.raw, probe)
        if (restricted) {
            hits =
                hits.filter { id ->
                    val run = stores.runStore().getById(id)
                    run != null && ScopeGuard.check(scope, run.rootId, stores.workItemRepository()) is Outcome.Ok
                }
        }
        return when (hits.size) {
            0 -> Outcome.Err(if (restricted) ScopedIds.denied(scope) else runNotFound(ref.raw))
            1 -> Outcome.Ok(hits.single())
            else ->
                Outcome.Err(
                    DomainError(
                        code = ErrorCode.AMBIGUOUS_ID,
                        message = "The id ${ref.raw} matches more than one run.",
                        detail = ErrorDetail.AmbiguousId(ref.raw, hits),
                        fixArgs = mapOf("prefix" to ref.raw)
                    )
                )
        }
    }

    /**
     * Resolves and loads the run [ref], then checks [scope] against the run root BEFORE the caller can reach any kind or
     * coordinator predicate: a run out of scope is `forbidden`, never an existence oracle, and under a restricted scope an
     * unknown run is `forbidden` too.
     */
    suspend fun loadRun(
        stores: RepositoryProvider,
        scope: AccessScope,
        ref: IdRef
    ): Outcome<Run> {
        val id =
            when (val r = resolveRunId(stores, scope, ref)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        val run =
            stores.runStore().getById(id)
                ?: return Outcome.Err(if (scope.isUnrestricted) runNotFound(id.toString()) else ScopedIds.denied(scope))
        return when (val guard = ScopeGuard.check(scope, run.rootId, stores.workItemRepository())) {
            is Outcome.Err -> guard
            is Outcome.Ok -> Outcome.Ok(run)
        }
    }

    /** `not_found` for a run, naming [id] as the caller wrote it. */
    fun runNotFound(id: String): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "No run matches $id.",
            detail = ErrorDetail.NotFound(EntityKind.RUN, id),
            fixArgs = mapOf("kind" to "run", "id" to id)
        )
}
