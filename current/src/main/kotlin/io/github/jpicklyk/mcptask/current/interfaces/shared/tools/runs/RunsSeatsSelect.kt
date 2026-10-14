package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsSeatsRequest
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/** `runs_read seats`: the seat assignments of one run. Orchestration only (the director surface has no seats). */
internal val runsSeatsSelect =
    SelectSpec.Single<RunsSeatsRequest, JsonObject>(
        name = "seats",
        doc =
            "List the seat assignments of one run, oldest first: active ones, or every one with ended:true, " +
                "optionally only those on one item. The coordinator pseudo-seat has no itemId. Tokens are never shown.",
        profiles = orchestration(),
        request = serializer<RunsSeatsRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> listSeats(env, ctx, req) },
        data = { it }
    )

private suspend fun listSeats(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsSeatsRequest
): Outcome<JsonObject> {
    val resolved =
        env.unitOfWork.read {
            val run =
                when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return@read Outcome.Err(r.error)
                }
            val itemId: UUID? =
                req.itemId?.let { ref ->
                    when (val r = ScopedIds.resolve(stores.workItemRepository(), ctx.scope, ref, "/itemId")) {
                        is Outcome.Ok -> r.value
                        is Outcome.Err -> return@read Outcome.Err(r.error)
                    }
                }
            Outcome.Ok<Pair<UUID, UUID?>>(run.id to itemId)
        }
    val (runId, itemId) =
        when (resolved) {
            is Outcome.Ok -> resolved.value
            is Outcome.Err -> return Outcome.Err(resolved.error)
        }
    val seats = env.seatService.list(runId, itemId, includeEnded = req.ended == true)
    return Outcome.Ok(
        buildJsonObject {
            put("runId", runId.toString())
            put("seats", buildJsonArray { seats.forEach { add(SeatView.of(it)) } })
        }
    )
}

/**
 * The one wire form of a seat assignment. It never carries the token, its hash or the bound principal; usage cost is
 * `costUsd` (decimal US dollars), never micro-USD. Absent members are omitted.
 */
internal object SeatView {
    fun of(seat: RunSeat): JsonObject =
        buildJsonObject {
            put("assignmentId", seat.id.toString())
            seat.itemId?.let { put("itemId", it.toString()) }
            put("seat", seat.seat)
            put("attempt", seat.attempt)
            put("status", seat.status.wire)
            seat.outcome?.let { put("outcome", it.wire) }
            seat.summary?.let { put("summary", it) }
            if (seat.links.isNotEmpty()) {
                put(
                    "links",
                    buildJsonArray {
                        seat.links.forEach { link ->
                            add(
                                buildJsonObject {
                                    put("kind", link.kind)
                                    put("ref", link.ref)
                                }
                            )
                        }
                    }
                )
            }
            if (seat.hints.isNotEmpty()) put("hints", JsonObject(seat.hints.mapValues { JsonPrimitive(it.value) }))
            put("assignedAt", seat.assignedAt.toString())
            seat.endedAt?.let { put("endedAt", it.toString()) }
            seat.lastSeenAt?.let { put("lastSeenAt", it.toString()) }
            put(
                "usage",
                buildJsonObject {
                    put("tokens", seat.usageTokens)
                    put("costUsd", usd(seat.usageCostMicro))
                }
            )
        }

    private fun usd(micro: Long): Double =
        java.math.BigDecimal
            .valueOf(micro, 6)
            .toDouble()
}
