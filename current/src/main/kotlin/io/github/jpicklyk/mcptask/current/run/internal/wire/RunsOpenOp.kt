package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.run.Run
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.call.ScopedIds
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.run.RunOpenCommand
import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.run.internal.app.RunPermissions
import io.github.jpicklyk.mcptask.current.run.internal.app.RunWireKeys
import io.github.jpicklyk.mcptask.current.run.internal.app.RunWireKeys.putWithAlias
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsOpenRequest
import io.github.jpicklyk.mcptask.current.run.internal.app.RunsSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import java.util.UUID

/** runs_write open: a run over a set of items of one root. */
internal val runsOpenOp =
    OpSpec<RunToolServices, RunsOpenRequest, JsonObject, RunStoreProvider>(
        name = "open",
        doc =
            "Open a run on a root over up to 500 of its items. The caller is recorded as the opener. An item already in " +
                "an open run is duplicate. Returns the run and orchestratorToken, the run token, held by the run's orchestrator " +
                "(shown once; coordinatorToken carries the same value, deprecated, removed in 5.0; a keyed replay omits both and " +
                "returns tokenRedacted: true): pass it as the top-level seatToken to update, assign and close.",
        profiles = orchestration(),
        capability = ApiCapability.RUNS,
        services = RunToolServices::class,
        stores = RunStoreProvider::class,
        request = serializer<RunsOpenRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.DUPLICATE),
        execute = { env, ctx, req -> openRun(env, ctx, req) }
    )

private const val MAX_ITEMS = 500

private suspend fun WriteScope<RunStoreProvider>.openRun(
    env: RunToolServices,
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
        when (val r = RunsSupport.budget(req.budget)) {
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
    // The `coordinator` run-token row joins the op's unit: a run is never stored without it.
    val orchestrator =
        when (val r = env.seatService.openOrchestrator(opened, RunPermissions.callerId(ctx))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val view = RunsSupport.view(env, stores, opened, ctx.scope)
    return Outcome.Ok(
        buildJsonObject {
            for ((key, value) in view) put(key, value)
            putWithAlias(RunWireKeys.ORCHESTRATOR_TOKEN, JsonPrimitive(orchestrator.token))
        }
    )
}

/** Resolves every item ref under the call scope, in order; the first failure wins. */
internal suspend fun resolveItems(
    scope: WriteScope<RunStoreProvider>,
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
