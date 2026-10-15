package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsOpenRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.run.RunOpenCommand
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/** runs_write open: a run over a set of items of one root. */
internal val runsOpenOp =
    OpSpec<RunsOpenRequest, JsonObject>(
        name = "open",
        doc =
            "Open a run on a root over up to 500 of its items. The caller is recorded as the opener. An item already in " +
                "an open run is duplicate. Returns the run and coordinatorToken, the run's coordinator credential (shown " +
                "once; a keyed replay omits it and returns tokenRedacted: true): pass it as the top-level seatToken to " +
                "update, assign and close.",
        profiles = orchestration() + director(),
        capability = ApiCapability.RUNS,
        request = serializer<RunsOpenRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.DUPLICATE),
        execute = { env, ctx, req -> openRun(env, ctx, req) }
    )

private const val MAX_ITEMS = 500

private suspend fun WriteScope.openRun(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsOpenRequest
): Outcome<JsonObject> {
    if (req.items.size > MAX_ITEMS) {
        return Outcome.Err(RunsSupport.invalid("/items", "a run holds at most $MAX_ITEMS items", req.items.size.toString()))
    }
    val repo = stores.workItemRepository()
    val rootId =
        when (val r = ScopedIds.resolve(repo, ctx.scope, req.rootId, "/rootId")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    when (val guard = ScopeGuard.check(ctx.scope, rootId, repo)) {
        is Outcome.Err -> return guard
        is Outcome.Ok -> Unit
    }
    val items =
        when (val r = resolveItems(this, ctx, req.items, "/items")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val scopeId =
        if (req.scopeId == null) {
            null
        } else {
            when (val r = ScopedIds.resolve(repo, ctx.scope, req.scopeId, "/scopeId")) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        }
    if (scopeId != null && repo.getById(scopeId) == null) {
        return Outcome.Err(RunsSupport.notFoundItem(scopeId))
    }
    val budget =
        when (val r = RunsSupport.budget(req.budget, "/budget")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val command =
        RunOpenCommand(
            rootId = rootId,
            items = items,
            scopeId = scopeId,
            label = req.label,
            title = req.title,
            budget = budget,
            stallAfterSeconds = req.stallAfterSeconds,
            hints = req.hints.orEmpty(),
            openedBy = RunPermissions.callerId(ctx),
            openedByKind = RunPermissions.openerKind(ctx)
        )
    val opened: Run =
        when (val r = RunsSupport.wire(env.runService.open(command))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    // The coordinator pseudo-seat joins the op's unit: a run is never stored without its coordinator.
    val coordinator =
        when (val r = env.seatService.openCoordinator(opened, RunPermissions.callerId(ctx))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val view = RunsSupport.view(env, stores, opened, ctx.scope)
    return Outcome.Ok(
        buildJsonObject {
            for ((key, value) in view) put(key, value)
            put("coordinatorToken", coordinator.token)
        }
    )
}

/** Resolves every item ref under the call scope, in order; the first failure wins. */
internal suspend fun resolveItems(
    scope: WriteScope,
    ctx: CallContext,
    refs: List<IdRef>,
    field: String
): Outcome<List<UUID>> {
    val repo = scope.stores.workItemRepository()
    val out = ArrayList<UUID>(refs.size)
    refs.forEachIndexed { index, ref ->
        when (val r = ScopedIds.resolve(repo, ctx.scope, ref, "$field/$index")) {
            is Outcome.Ok -> out += r.value
            is Outcome.Err -> return r
        }
    }
    return Outcome.Ok(out)
}
