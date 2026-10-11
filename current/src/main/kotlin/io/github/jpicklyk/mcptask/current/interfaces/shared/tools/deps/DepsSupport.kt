package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.IdResolver
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.deps.DepType
import io.github.jpicklyk.mcptask.current.application.api.toOutcome
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Helpers shared by the `deps_read` and `deps_write` handlers. */
internal object DepsSupport {
    /** Visit cap of `graph` (the 3.x `MAX_DEPENDENCY_GRAPH_NODES`). */
    const val MAX_GRAPH_NODES = 1000

    /** One edge as it appears on the wire. */
    fun edge(dep: Dependency): JsonObject {
        val n = dep.normalized()
        return buildJsonObject {
            put("id", n.id.toString())
            put("from", n.fromItemId.toString())
            put("to", n.toItemId.toString())
            put("type", (DepType.of(n.type) ?: DepType.BLOCKS).wire)
            n.unblockAt?.let { put("unblockAt", it.lowercase()) }
        }
    }

    /** Resolves [ref] to an item id, or its `not_found` / `ambiguous_id` error. */
    suspend fun resolve(
        env: ToolExecutionContext,
        ref: IdRef,
        field: String
    ): Outcome<UUID> = IdResolver(env.repositoryProvider.itemStore()).resolve(ref).toOutcome(field)

    /** Resolves every ref in order; the first failure wins. */
    suspend fun resolveAll(
        env: ToolExecutionContext,
        refs: List<IdRef>,
        field: String
    ): Outcome<List<UUID>> {
        val out = ArrayList<UUID>(refs.size)
        for (ref in refs) {
            when (val r = resolve(env, ref, field)) {
                is Outcome.Ok -> out += r.value
                is Outcome.Err -> return r
            }
        }
        return Outcome.Ok(out)
    }

    /** The first id of [ids] the call's scope does not admit, as a `forbidden` error; null when all are admitted. */
    suspend fun forbiddenIfAny(
        ctx: CallContext,
        ids: Collection<UUID>,
        repo: WorkItemRepository
    ): DomainError? {
        if (ctx.scope.isUnrestricted) return null
        val admitted = ScopeGuard.admitted(ctx.scope, ids.toSet(), repo)
        val denied = ids.firstOrNull { it !in admitted } ?: return null
        return when (val check = ScopeGuard.check(ctx.scope, denied, repo)) {
            is Outcome.Err -> check.error
            is Outcome.Ok ->
                if (ctx.scope.rootIds != null) {
                    ScopeGuard.forbidden(ForbiddenScope.ROOT, ctx.scope.rootIds.map { it.toString() })
                } else {
                    ScopeGuard.forbidden(ForbiddenScope.TAG, ctx.scope.tagsInclude)
                }
        }
    }

    /** `not_found` (kind item) for the first of [ids] that has no item; null when all exist. */
    suspend fun missingItem(
        ids: Collection<UUID>,
        repo: WorkItemRepository
    ): DomainError? {
        val distinct = ids.toSet()
        if (distinct.isEmpty()) return null
        val found = repo.findByIds(distinct).map { it.id }.toSet()
        val missing = ids.firstOrNull { it !in found } ?: return null
        return notFound(EntityKind.ITEM, missing.toString())
    }

    fun notFound(
        kind: EntityKind,
        id: String
    ): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "No ${kind.name.lowercase()} matches $id.",
            detail = ErrorDetail.NotFound(kind, id),
            fixArgs = mapOf("kind" to kind.name.lowercase(), "id" to id)
        )
}

/**
 * One page of [all] for [page], built by [ListPage.of] (U22: `total` is the full count on every page; `truncated` and
 * `next` derive from the page offset). [capped] marks a lower-bound total (U15).
 */
internal fun pageOfAll(
    all: List<JsonObject>,
    page: PageRequest,
    capped: Boolean = false
): ListPage<JsonObject> = ListPage.of(all.drop(page.offset).take(page.limit), all.size, page, capped)
