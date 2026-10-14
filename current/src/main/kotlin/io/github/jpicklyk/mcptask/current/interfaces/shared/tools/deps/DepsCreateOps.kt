package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.deps.DepType
import io.github.jpicklyk.mcptask.current.application.api.deps.DepsCreateRequest
import io.github.jpicklyk.mcptask.current.application.api.deps.DepsFanInRequest
import io.github.jpicklyk.mcptask.current.application.api.deps.DepsFanOutRequest
import io.github.jpicklyk.mcptask.current.application.api.deps.DepsLinearRequest
import io.github.jpicklyk.mcptask.current.application.api.deps.UnblockAt
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.ClaimHolder
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

private val CREATE_ERRORS =
    setOf(
        ErrorCode.NOT_FOUND,
        ErrorCode.AMBIGUOUS_ID,
        ErrorCode.FORBIDDEN,
        ErrorCode.DUPLICATE,
        ErrorCode.CYCLE_DETECTED,
        ErrorCode.NOT_CLAIM_HOLDER,
        ErrorCode.UNAUTHENTICATED
    )

/** deps_write create: one edge. */
internal val depsCreateOp =
    OpSpec<DepsCreateRequest, JsonObject>(
        name = "create",
        doc = "Create one dependency edge: from blocks to (type blocks, the default) or relates_to. A duplicate or a cycle is rejected.",
        capability = ApiCapability.MANAGE_DEPENDENCIES,
        profiles = knowledge(),
        request = serializer<DepsCreateRequest>(),
        errors = CREATE_ERRORS,
        execute = { env, ctx, req ->
            val ids =
                when (val r = DepsSupport.resolveAll(env, ctx, listOf(req.from, req.to), "/from")) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return@OpSpec r
                }
            val from = ids[0]
            val to = ids[1]
            val faults = shapeFaults(req.type, req.unblockAt)
            if (from == to) faults += FieldViolation("/to", "must differ from from", req.to.raw)
            if (faults.isNotEmpty()) return@OpSpec Outcome.Err(invalidFields(faults))
            createEdges(env, ctx, ids, listOf(Pair(from, to)), req.type, req.unblockAt).map { DepsSupport.edge(it.single()) }
        }
    )

/** deps_write linear: a chain. */
internal val depsLinearOp =
    OpSpec<DepsLinearRequest, JsonObject>(
        name = "linear",
        doc = "Create a chain: each item in ids blocks the next. All edges are created together or none.",
        capability = ApiCapability.MANAGE_DEPENDENCIES,
        profiles = knowledge(),
        request = serializer<DepsLinearRequest>(),
        errors = CREATE_ERRORS,
        execute = { env, ctx, req ->
            if (req.ids.size < 2) {
                return@OpSpec Outcome.Err(
                    invalidFields(listOf(FieldViolation("/ids", "must have at least 2 items", req.ids.size.toString())))
                )
            }
            val ids =
                when (val r = DepsSupport.resolveAll(env, ctx, req.ids, "/ids")) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return@OpSpec r
                }
            val faults = shapeFaults(req.type, req.unblockAt)
            if (ids.zipWithNext().any { (a, b) -> a == b }) {
                faults +=
                    FieldViolation("/ids", "must not repeat an item in consecutive positions")
            }
            if (faults.isNotEmpty()) return@OpSpec Outcome.Err(invalidFields(faults))
            createEdges(env, ctx, ids, ids.zipWithNext(), req.type, req.unblockAt).map { created(it) }
        }
    )

/** deps_write fan_out: one source to many targets. */
internal val depsFanOutOp =
    OpSpec<DepsFanOutRequest, JsonObject>(
        name = "fan_out",
        doc = "Create from to each of toIds. All edges are created together or none.",
        capability = ApiCapability.MANAGE_DEPENDENCIES,
        profiles = knowledge(),
        request = serializer<DepsFanOutRequest>(),
        errors = CREATE_ERRORS,
        execute = { env, ctx, req ->
            if (req.toIds.isEmpty()) {
                return@OpSpec Outcome.Err(invalidFields(listOf(FieldViolation("/toIds", "must have at least 1 item", "0"))))
            }
            val from =
                when (val r = DepsSupport.resolve(env, ctx, req.from, "/from")) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return@OpSpec r
                }
            val targets =
                when (val r = DepsSupport.resolveAll(env, ctx, req.toIds, "/toIds")) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return@OpSpec r
                }
            val faults = shapeFaults(req.type, req.unblockAt)
            if (from in targets) faults += FieldViolation("/toIds", "must not contain from", req.from.raw)
            if (faults.isNotEmpty()) return@OpSpec Outcome.Err(invalidFields(faults))
            createEdges(env, ctx, listOf(from) + targets, targets.map { Pair(from, it) }, req.type, req.unblockAt).map { created(it) }
        }
    )

/** deps_write fan_in: many sources to one target. */
internal val depsFanInOp =
    OpSpec<DepsFanInRequest, JsonObject>(
        name = "fan_in",
        doc = "Create each of fromIds to the to item. All edges are created together or none.",
        capability = ApiCapability.MANAGE_DEPENDENCIES,
        profiles = knowledge(),
        request = serializer<DepsFanInRequest>(),
        errors = CREATE_ERRORS,
        execute = { env, ctx, req ->
            if (req.fromIds.isEmpty()) {
                return@OpSpec Outcome.Err(invalidFields(listOf(FieldViolation("/fromIds", "must have at least 1 item", "0"))))
            }
            val sources =
                when (val r = DepsSupport.resolveAll(env, ctx, req.fromIds, "/fromIds")) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return@OpSpec r
                }
            val target =
                when (val r = DepsSupport.resolve(env, ctx, req.to, "/to")) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return@OpSpec r
                }
            val faults = shapeFaults(req.type, req.unblockAt)
            if (target in sources) faults += FieldViolation("/fromIds", "must not contain to", req.to.raw)
            if (faults.isNotEmpty()) return@OpSpec Outcome.Err(invalidFields(faults))
            createEdges(env, ctx, sources + target, sources.map { Pair(it, target) }, req.type, req.unblockAt).map { created(it) }
        }
    )

private fun created(deps: List<Dependency>): JsonObject = buildJsonObject { put("created", JsonArray(deps.map { DepsSupport.edge(it) })) }

/** The Dependency invariant faults that depend only on the type and threshold. */
private fun shapeFaults(
    type: DepType?,
    unblockAt: UnblockAt?
): MutableList<FieldViolation> {
    val faults = ArrayList<FieldViolation>()
    if (type == DepType.RELATES_TO && unblockAt != null) {
        faults += FieldViolation("/unblockAt", "is not allowed with relates_to", unblockAt.wire)
    }
    return faults
}

/**
 * Steps c to e of the per-element check order: scope over every named item, then existence, then ONE
 * createInUnit over all edges, which is what makes a pattern atomic. The service fences every endpoint for the call's
 * caller ([ClaimHolder.resolve]).
 */
private suspend fun WriteScope.createEdges(
    env: ToolExecutionContext,
    ctx: CallContext,
    ids: List<UUID>,
    edges: List<Pair<UUID, UUID>>,
    type: DepType?,
    unblockAt: UnblockAt?
): Outcome<List<Dependency>> {
    val items = stores.workItemRepository()
    DepsSupport.forbiddenIfAny(ctx, ids, items)?.let { return Outcome.Err(it) }
    DepsSupport.missingItem(ids, items)?.let { return Outcome.Err(it) }
    val caller = ClaimHolder.resolve(ctx.principal.claim, ctx.seat?.assignmentId)
    val deps =
        edges.map { (from, to) ->
            Dependency(
                fromItemId = from,
                toItemId = to,
                type = (type ?: DepType.BLOCKS).domain,
                unblockAt = unblockAt?.wire
            )
        }
    return when (val r = env.dependencyCommandService.createInUnit(deps, caller)) {
        is Outcome.Ok -> r
        is Outcome.Err -> Outcome.Err(redactCyclePath(ctx, r.error, ids.toSet(), items))
    }
}

/**
 * Under a restricted scope, a `cycle_detected` path lists only the ids the caller's scope admits, in path order; a hop
 * through an item outside the scope is omitted (fail closed: scope is no topology oracle). The request's [named] ids were
 * admitted already, so only the other hops cost one [ScopeGuard.admitted] batch call. An unrestricted caller, or any
 * other error, is returned unchanged.
 */
private suspend fun redactCyclePath(
    ctx: CallContext,
    error: DomainError,
    named: Set<UUID>,
    items: WorkItemRepository
): DomainError {
    val detail = error.detail as? ErrorDetail.CycleDetected ?: return error
    if (ctx.scope.isUnrestricted) return error
    val others = detail.path.filterTo(LinkedHashSet()) { it !in named }
    val admitted = named + ScopeGuard.admitted(ctx.scope, others, items)
    val visible = detail.path.filter { it in admitted }
    // the closing edge's ends are named ids, so visible is never empty; the fallback only keeps the detail valid
    return error.copy(detail = ErrorDetail.CycleDetected(visible.ifEmpty { named.take(1) }))
}
