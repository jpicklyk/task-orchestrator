package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.application.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import java.util.UUID

/**
 * Scope-aware id resolution, the ONE rule every tool applies to a caller-supplied item id (Q32 hoisted it out of
 * `items_read`, plan decision D2), failing closed so scope is never an existence oracle:
 *
 * - unrestricted scope: the plain [IdResolver] outcome (`not_found` / `ambiguous_id` / `invalid_request` on [field]);
 * - restricted scope: no match is `forbidden` (never `not_found`); an ambiguous prefix keeps only the candidates
 *   [ScopeGuard] admits: none is `forbidden`, one resolves, two or more is `ambiguous_id` listing only those.
 *
 * [resolve] does NOT scope-check the resolved id (a filter id is left to the store's scope filter); [resolveTarget]
 * does, so a target the caller may not see is `forbidden`.
 */
object ScopedIds {
    /** Resolves [ref] (request field [field], a JSON pointer) under [scope]; see the class rules. */
    suspend fun resolve(
        repo: WorkItemRepository,
        scope: AccessScope,
        ref: IdRef,
        field: String
    ): Outcome<UUID> = admit(repo, scope, IdResolver(repo).resolve(ref), field)

    /** [resolve] for a raw id string. */
    suspend fun resolve(
        repo: WorkItemRepository,
        scope: AccessScope,
        raw: String,
        field: String
    ): Outcome<UUID> = admit(repo, scope, IdResolver(repo).resolve(raw), field)

    /** [resolve] followed by [ScopeGuard.check]: a target id the caller may not see is `forbidden`. */
    suspend fun resolveTarget(
        repo: WorkItemRepository,
        scope: AccessScope,
        ref: IdRef,
        field: String
    ): Outcome<UUID> = checked(repo, scope, resolve(repo, scope, ref, field))

    /** [resolveTarget] for a raw id string. */
    suspend fun resolveTarget(
        repo: WorkItemRepository,
        scope: AccessScope,
        raw: String,
        field: String
    ): Outcome<UUID> = checked(repo, scope, resolve(repo, scope, raw, field))

    /** The `forbidden` error a restricted [scope] answers for an id it cannot admit (ScopeGuard's missing-item rule). */
    fun denied(scope: AccessScope): DomainError {
        val roots = scope.rootIds
        return if (roots != null) {
            ScopeGuard.forbidden(ForbiddenScope.ROOT, roots.map { it.toString() })
        } else {
            ScopeGuard.forbidden(ForbiddenScope.TAG, scope.tagsInclude)
        }
    }

    private suspend fun admit(
        repo: WorkItemRepository,
        scope: AccessScope,
        resolution: IdResolution,
        field: String
    ): Outcome<UUID> {
        if (scope.isUnrestricted) return resolution.toOutcome(field)
        return when (resolution) {
            is IdResolution.NotFound -> Outcome.Err(denied(scope))
            is IdResolution.Ambiguous -> {
                val admitted = resolution.candidates.filter { ScopeGuard.check(scope, it, repo) is Outcome.Ok }
                when (admitted.size) {
                    0 -> Outcome.Err(denied(scope))
                    1 -> Outcome.Ok(admitted.single())
                    else -> IdResolution.Ambiguous(resolution.prefix, admitted).toOutcome(field)
                }
            }
            else -> resolution.toOutcome(field)
        }
    }

    private suspend fun checked(
        repo: WorkItemRepository,
        scope: AccessScope,
        resolved: Outcome<UUID>
    ): Outcome<UUID> =
        when (resolved) {
            is Outcome.Err -> resolved
            is Outcome.Ok -> ScopeGuard.check(scope, resolved.value, repo).map { resolved.value }
        }
}
