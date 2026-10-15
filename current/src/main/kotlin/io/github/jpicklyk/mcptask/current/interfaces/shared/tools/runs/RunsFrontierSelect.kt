package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsFrontierRequest
import io.github.jpicklyk.mcptask.current.application.read.FrontierWaitReason
import io.github.jpicklyk.mcptask.current.application.read.RunFrontier
import io.github.jpicklyk.mcptask.current.application.read.RunFrontierQueries
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ItemView
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** `runs_read frontier`: what a run can do next. */
internal val runsFrontierSelect =
    SelectSpec.Single<RunsFrontierRequest, JsonObject>(
        name = "frontier",
        doc =
            "A run's frontier: member counts by role, the ready members (no active seat, no unmet dependency, no " +
                "claim held outside the run; none on a closed run), the active seats with their stalled flag, and the " +
                "waiting members with a reason. Members and blockers outside the call scope are left out.",
        profiles = orchestration() + director(),
        request = serializer<RunsFrontierRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> frontier(env, ctx, req) },
        data = { it }
    )

private suspend fun frontier(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsFrontierRequest
): Outcome<JsonObject> =
    env.unitOfWork.read {
        val run =
            when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return@read Outcome.Err(r.error)
            }
        val found =
            RunFrontierQueries(env.unitOfWork).frontier(run.id, env.clock.now(), ctx.scope)
                ?: return@read Outcome.Err(RunsSupport.runNotFound(run.id.toString()))
        Outcome.Ok(FrontierView.of(found))
    }

/**
 * The wire form of a run frontier. `runId` is the full UUID; item ids are 8-hex short ids. It never carries a body,
 * summary, token, principal or assignment id; absent members are omitted, never null; every array is present.
 */
internal object FrontierView {
    fun of(frontier: RunFrontier): JsonObject =
        buildJsonObject {
            put("runId", frontier.run.id.toString())
            put("status", frontier.run.status.wire)
            put("stalled", frontier.stalled)
            put(
                "counts",
                buildJsonObject { Role.entries.forEach { put(role(it), frontier.counts[it] ?: 0) } }
            )
            put(
                "ready",
                buildJsonArray {
                    frontier.ready.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", ItemView.shortId(item.id))
                                put("title", item.title)
                                item.role?.let { put("role", role(it)) }
                                item.type?.let { put("type", it) }
                            }
                        )
                    }
                }
            )
            put(
                "inFlight",
                buildJsonArray {
                    frontier.inFlight.forEach { seat ->
                        add(
                            buildJsonObject {
                                put("id", ItemView.shortId(seat.itemId))
                                seat.role?.let { put("role", role(it)) }
                                put("seat", seat.seat)
                                put("attempt", seat.attempt)
                                seat.lastSeenAt?.let { put("lastSeenAt", it.toString()) }
                                put("stalled", seat.stalled)
                            }
                        )
                    }
                }
            )
            put(
                "waiting",
                buildJsonArray {
                    frontier.waiting.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("id", ItemView.shortId(entry.itemId))
                                put("reason", entry.reason.wire)
                                if (entry.reason == FrontierWaitReason.DEPENDENCY) {
                                    put("blockers", buildJsonArray { entry.blockers.forEach { add(ItemView.shortId(it)) } })
                                }
                            }
                        )
                    }
                }
            )
        }

    private fun role(role: Role): String = role.name.lowercase()
}
