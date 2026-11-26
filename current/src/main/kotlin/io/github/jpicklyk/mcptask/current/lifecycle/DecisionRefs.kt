package io.github.jpicklyk.mcptask.current.lifecycle

import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.EntityKind
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ScopedIds
import io.github.jpicklyk.mcptask.current.platform.port.AccessScope
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard
import java.util.UUID

/** A decision id as a caller writes it (a full UUID or a hex prefix), resolved under the call scope. */
internal object DecisionRefs {
    private const val UUID_LENGTH = 36
    private const val PREFIX_PROBE = 2
    private const val RESTRICTED_PREFIX_PROBE = 200

    /**
     * Resolves a decision id as the caller wrote it, under [scope]: a full UUID resolves with no read, a hex prefix through
     * the store. Unrestricted scope: no match is `not_found`, two or more `ambiguous_id`. Restricted scope: no match is
     * `forbidden` (never `not_found`), and an ambiguous prefix keeps only the candidates whose root [ScopeGuard] admits.
     */
    suspend fun resolveId(
        stores: LifecycleStoreProvider,
        scope: AccessScope,
        ref: IdRef
    ): Outcome<UUID> {
        if (ref.raw.length == UUID_LENGTH) return Outcome.Ok(UUID.fromString(ref.raw))
        val restricted = !scope.isUnrestricted
        val store = stores.decisionReads()
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

    fun notFound(raw: String): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "No decision matches $raw.",
            detail = ErrorDetail.NotFound(EntityKind.DECISION, raw),
            fixArgs = mapOf("kind" to "decision", "id" to raw)
        )
}
