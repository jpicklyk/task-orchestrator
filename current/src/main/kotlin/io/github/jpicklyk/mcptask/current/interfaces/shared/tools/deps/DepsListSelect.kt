package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.deps.DepDirection
import io.github.jpicklyk.mcptask.current.application.api.deps.DepsListRequest
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.util.UUID

/** `deps_read list`: the edges touching one item, oldest first. */
internal val depsListSelect =
    SelectSpec.Paged<DepsListRequest, JsonObject>(
        name = "list",
        doc =
            "List the dependency edges touching one item, oldest first. incoming = what blocks it, outgoing = what it blocks. " +
                "Edges whose other end is outside the scope of the call are left out and not counted.",
        profiles = knowledge() + director(),
        request = serializer<DepsListRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> listEdges(env, ctx, req) },
        item = { it }
    )

private suspend fun listEdges(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: DepsListRequest
): Outcome<ListPage<JsonObject>> {
    val item =
        when (val r = DepsSupport.resolve(env, ctx, req.item, "/item")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val page =
        when (val p = PageRequest.from(req.limit, req.cursor)) {
            is Outcome.Ok -> p.value
            is Outcome.Err -> return p
        }
    return env.unitOfWork.read {
        val items = stores.workItemRepository()
        when (val guard = ScopeGuard.check(ctx.scope, item, items)) {
            is Outcome.Err -> return@read guard
            is Outcome.Ok -> Unit
        }
        if (items.getById(item) == null) return@read Outcome.Err(DepsSupport.notFound(EntityKind.ITEM, item.toString()))

        val direction = req.direction ?: DepDirection.ALL
        val rows =
            stores
                .dependencyRepository()
                .findByItemId(item)
                .map { it.normalized() }
                .filter { dep ->
                    when (direction) {
                        DepDirection.INCOMING -> dep.toItemId == item
                        DepDirection.OUTGOING -> dep.fromItemId == item
                        DepDirection.ALL -> true
                    } &&
                        (req.type == null || dep.type == req.type.domain)
                }
        val counterparty = { dep: Dependency -> if (dep.fromItemId == item) dep.toItemId else dep.fromItemId }
        val admitted = ScopeGuard.admitted(ctx.scope, rows.mapTo(HashSet<UUID>()) { counterparty(it) }, items)
        val visible =
            rows
                .filter { counterparty(it) in admitted }
                .sortedWith(compareBy<Dependency> { it.createdAt }.thenBy { it.id })
        Outcome.Ok(pageOfAll(visible.map { DepsSupport.edge(it) }, page))
    }
}
