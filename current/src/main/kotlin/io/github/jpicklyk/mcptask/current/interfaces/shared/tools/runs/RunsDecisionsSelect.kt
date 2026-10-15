package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsDecisionsRequest
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.util.UUID

/** `runs_read decisions`: decision requests, newest first. */
internal val runsDecisionsSelect =
    SelectSpec.Paged<RunsDecisionsRequest, JsonObject>(
        name = "decisions",
        doc =
            "List decisions, newest first (raisedAt descending): id, class, severity, effectiveSeverity (one step up " +
                "while overdue), status, overdue and the first 120 characters of the question. runId, itemId and rootId " +
                "filter; overdue true keeps the pending decisions past their expiry. No status filter: filter on status " +
                "client-side. Decisions on roots outside the call scope are left out.",
        profiles = director(),
        request = serializer<RunsDecisionsRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> listDecisions(env, ctx, req) },
        item = { it }
    )

private suspend fun listDecisions(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsDecisionsRequest
): Outcome<ListPage<JsonObject>> {
    val page =
        when (val p = PageRequest.from(req.limit, req.cursor)) {
            is Outcome.Ok -> p.value
            is Outcome.Err -> return p
        }
    val repo = env.repositoryProvider.workItemRepository()
    val rootId: UUID? =
        req.rootId?.let { ref ->
            when (val r = ScopedIds.resolveTarget(repo, ctx.scope, ref, "/rootId")) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        }
    val itemId: UUID? =
        req.itemId?.let { ref ->
            when (val r = ScopedIds.resolveTarget(repo, ctx.scope, ref, "/itemId")) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        }
    return env.unitOfWork.read {
        val runId: UUID? =
            req.runId?.let { ref ->
                when (val r = RunsSupport.loadRun(stores, ctx, ref)) {
                    is Outcome.Ok -> r.value.id
                    is Outcome.Err -> return@read r
                }
            }
        val found = stores.decisionRequestStore().list(rootId?.let { setOf(it) }, runId, itemId)
        val admitted = ScopeGuard.admitted(ctx.scope, found.mapTo(HashSet()) { it.rootId }, stores.workItemRepository())
        val now = env.clock.now()
        val visible = found.filter { it.rootId in admitted && (req.overdue != true || it.isOverdue(now)) }
        val views = visible.drop(page.offset).take(page.limit).map { DecisionView.summary(it, now) }
        Outcome.Ok(ListPage.of(views, visible.size, page))
    }
}
