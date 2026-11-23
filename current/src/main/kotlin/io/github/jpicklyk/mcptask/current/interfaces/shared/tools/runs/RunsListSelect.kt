package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.runs.RunsListRequest
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.run.RunStatus
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.call.ScopedIds
import io.github.jpicklyk.mcptask.current.platform.port.ListPage
import io.github.jpicklyk.mcptask.current.platform.port.PageRequest
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.platform.tool.SelectSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.util.UUID

/** `runs_read list`: runs, newest first. */
internal val runsListSelect =
    SelectSpec.Paged<RunToolServices, RunsListRequest, JsonObject>(
        name = "list",
        doc =
            "List runs, newest first (openedAt descending). status, rootId and endedSince (closedAt at or after, " +
                "inclusive) filter; unmarked names a mark key and keeps only closed runs without it, so it cannot be " +
                "combined with a status other than closed. Runs on roots outside the call scope are left out.",
        profiles = orchestration() + director(),
        services = RunToolServices::class,
        request = serializer<RunsListRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID),
        handler = { env, ctx, req -> listRuns(env, ctx, req) },
        item = { it }
    )

private suspend fun listRuns(
    env: RunToolServices,
    ctx: CallContext,
    req: RunsListRequest
): Outcome<ListPage<JsonObject>> {
    val page =
        when (val p = PageRequest.from(req.limit, req.cursor)) {
            is Outcome.Ok -> p.value
            is Outcome.Err -> return p
        }
    if (req.unmarked != null) {
        if (!RunsSupport.MARK_KEY.matches(req.unmarked)) {
            return Outcome.Err(RunsSupport.invalid("/unmarked", "must be a mark key", req.unmarked))
        }
        if (req.status != null && req.status.domain != RunStatus.CLOSED) {
            return Outcome.Err(
                RunsSupport.invalid("/unmarked", "only applies to closed runs; status must be closed or absent", req.unmarked)
            )
        }
    }
    val rootId: UUID? =
        if (req.rootId == null) {
            null
        } else {
            when (val r = ScopedIds.resolve(env.stores.workItemRepository(), ctx.scope, req.rootId, "/rootId")) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        }
    val status = if (req.unmarked != null) RunStatus.CLOSED else req.status?.domain
    return env.unitOfWork.read {
        val found = stores.runStore().list(rootId?.let { setOf(it) }, status, req.endedSince)
        val admitted = ScopeGuard.admitted(ctx.scope, found.mapTo(HashSet()) { it.rootId }, stores.workItemRepository())
        val visible =
            found.filter { run ->
                run.rootId in admitted && (req.unmarked == null || req.unmarked !in run.marks)
            }
        val slice = visible.drop(page.offset).take(page.limit)
        val now = env.clock.now()
        val views =
            slice.map { run ->
                RunsSupport.view(stores, run, ctx.scope, now)
            }
        Outcome.Ok(ListPage.of(views, visible.size, page))
    }
}
