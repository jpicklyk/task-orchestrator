package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsEndSeatRequest
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatLink
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** runs_write end_seat: the calling seat ends its own assignment. */
internal val runsEndSeatOp =
    OpSpec<RunsEndSeatRequest, JsonObject>(
        name = "end_seat",
        doc =
            "End the calling seat's own assignment with an outcome (done, failed or abandoned), an optional summary " +
                "(at most 500 characters) and up to 10 links. Needs the seat's own token; the coordinator token is " +
                "refused. Releases the claims the seat holds and reports them in released; its token stops working.",
        profiles = orchestration(),
        capability = ApiCapability.RUNS,
        request = serializer<RunsEndSeatRequest>(),
        errors = setOf(ErrorCode.FORBIDDEN, ErrorCode.UNAUTHENTICATED),
        execute = { env, ctx, req -> endSeat(env, ctx, req) }
    )

private const val LINK_KIND = "kind"
private const val LINK_REF = "ref"

private suspend fun WriteScope.endSeat(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsEndSeatRequest
): Outcome<JsonObject> {
    if (!RunPermissions.isMemberSeat(ctx)) return Outcome.Err(RunPermissions.refuse(RunPermissions.END_SEAT_REQUIRED))
    val seat = checkNotNull(ctx.seat)
    val links =
        when (val r = links(req.links)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val result =
        when (val r = env.seatService.endSeat(seat.assignmentId, req.outcome.domain, req.summary, links)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val ended = result.seat
    val paused =
        when (val r = env.runLimits.checkBudget(ended.runId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    return Outcome.Ok(
        buildJsonObject {
            put("assignmentId", ended.id.toString())
            ended.itemId?.let { put("itemId", it.toString()) }
            put("seat", ended.seat)
            put("attempt", ended.attempt)
            ended.outcome?.let { put("outcome", it.wire) }
            ended.endedAt?.let { put("endedAt", it.toString()) }
            put("released", buildJsonArray { result.released.forEach { add(JsonPrimitive(it.toString())) } })
        }.withPaused(paused)
    )
}

/**
 * The request [raw] links as seat links. Each element must be an object of exactly `kind` and `ref`, both strings; a
 * missing or non-string member is `/links/<i>/kind` or `/links/<i>/ref`, another member is `/links/<i>`. The kind
 * vocabulary and the length limits are checked by the service that takes them (seats, and decisions on `raise`).
 */
internal fun links(raw: List<JsonObject>?): Outcome<List<RunSeatLink>> {
    if (raw == null) return Outcome.Ok(emptyList())
    val out = ArrayList<RunSeatLink>(raw.size)
    val faults = ArrayList<FieldViolation>()
    raw.forEachIndexed { i, obj ->
        val extra = obj.keys.filter { it != LINK_KIND && it != LINK_REF }
        if (extra.isNotEmpty()) faults += FieldViolation("/links/$i", "unknown member(s): ${extra.sorted().joinToString(", ")}")
        val kind = text(obj[LINK_KIND])
        val ref = text(obj[LINK_REF])
        if (kind == null) faults += FieldViolation("/links/$i/$LINK_KIND", "is required and must be a string")
        if (ref == null) faults += FieldViolation("/links/$i/$LINK_REF", "is required and must be a string")
        if (kind != null && ref != null) out += RunSeatLink(kind, ref)
    }
    return if (faults.isEmpty()) Outcome.Ok(out) else Outcome.Err(invalidFields(faults))
}

private fun text(value: kotlinx.serialization.json.JsonElement?): String? =
    when (value) {
        null, is JsonNull, is JsonObject, is JsonArray -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content
    }
