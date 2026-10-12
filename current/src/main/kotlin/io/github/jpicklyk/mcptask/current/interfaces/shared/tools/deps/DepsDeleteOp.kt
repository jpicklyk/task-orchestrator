package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.deps.DepsDeleteRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

private val FULL_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** deps_write delete: by dependency id, by endpoints, or every edge of one item. */
internal val depsDeleteOp =
    OpSpec<DepsDeleteRequest, JsonObject>(
        name = "delete",
        doc =
            "Delete dependency edges. Give exactly one of: id (one edge, full UUID), from + to (with optional type), or " +
                "item (every edge touching it). An unknown id is not_found; a from/to or item that matches nothing deletes 0.",
        destructive = true,
        capability = ApiCapability.MANAGE_DEPENDENCIES,
        profile = ToolProfile.KNOWLEDGE,
        request = serializer<DepsDeleteRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        execute = { env, ctx, req ->
            shapeError(req)?.let { return@OpSpec Outcome.Err(it) }
            val result: Outcome<Int> =
                when {
                    req.id != null -> deleteById(env, ctx, req.id)
                    req.item != null -> deleteByItem(env, ctx, req.item)
                    else -> deleteByEnds(env, ctx, req)
                }
            result.map { n -> buildJsonObject { put("deleted", n) } }
        }
    )

private fun shapeError(req: DepsDeleteRequest): DomainError? {
    val forms = listOf(req.id != null, req.from != null || req.to != null, req.item != null).count { it }
    if (forms != 1) {
        return invalidFields(listOf(FieldViolation("/", "give exactly one of: id, from + to, item")))
    }
    val faults = ArrayList<FieldViolation>()
    if (req.id != null && !FULL_UUID.matches(req.id)) faults += FieldViolation("/id", "must be a full UUID", req.id)
    if ((req.from == null) != (req.to == null)) {
        faults += FieldViolation(if (req.from == null) "/from" else "/to", "is required together with the other end")
    }
    if (req.type != null && req.from == null && req.to == null) faults += FieldViolation("/type", "is only allowed with from and to")
    return if (faults.isEmpty()) null else invalidFields(faults)
}

private suspend fun WriteScope.deleteById(
    env: ToolExecutionContext,
    ctx: CallContext,
    rawId: String
): Outcome<Int> {
    val id = UUID.fromString(rawId)
    val dep =
        stores.dependencyRepository().findById(id)
            ?: return Outcome.Err(DepsSupport.notFound(EntityKind.DEPENDENCY, id.toString()))
    DepsSupport.forbiddenIfAny(ctx, listOf(dep.fromItemId, dep.toItemId), stores.workItemRepository())?.let { return Outcome.Err(it) }
    return withEventActor(ctx.principal?.claim) { env.dependencyCommandService.deleteById(id) }.map { if (it) 1 else 0 }
}

private suspend fun WriteScope.deleteByItem(
    env: ToolExecutionContext,
    ctx: CallContext,
    ref: IdRef
): Outcome<Int> {
    val item =
        when (val r = DepsSupport.resolve(env, ref, "/item")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val touched = LinkedHashSet<UUID>().apply { add(item) }
    for (dep in stores.dependencyRepository().findByItemId(item)) {
        touched += dep.fromItemId
        touched += dep.toItemId
    }
    DepsSupport.forbiddenIfAny(ctx, touched, stores.workItemRepository())?.let { return Outcome.Err(it) }
    return withEventActor(ctx.principal?.claim) { env.dependencyCommandService.deleteByItemId(item) }
}

private suspend fun WriteScope.deleteByEnds(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: DepsDeleteRequest
): Outcome<Int> {
    val ids =
        when (val r = DepsSupport.resolveAll(env, listOf(requireNotNull(req.from), requireNotNull(req.to)), "/from")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    DepsSupport.forbiddenIfAny(ctx, ids, stores.workItemRepository())?.let { return Outcome.Err(it) }
    return withEventActor(ctx.principal?.claim) {
        env.dependencyCommandService.deleteByRelationship(ids[0], ids[1], req.type?.domain)
    }
}
