package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.run.RunEndedSnapshot
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * The one wire form of a run, shared by `runs_read` (get and list items) and every `runs_write` op result. Absent
 * members are omitted, never null; `hints` and `marks` are always present. The budget cost is `costUsd` on the wire,
 * never micro-USD.
 */
internal object RunView {
    private const val MICRO_PER_USD = 1_000_000.0

    /** [members] is the member count and [activeSeats] the active seat count (0 until seats exist) at read time. */
    fun of(
        run: Run,
        members: Int,
        activeSeats: Int,
        now: Instant
    ): JsonObject =
        buildJsonObject {
            put("runId", run.id.toString())
            put("rootId", run.rootId.toString())
            run.scopeId?.let { put("scopeId", it.toString()) }
            run.label?.let { put("label", it) }
            run.title?.let { put("title", it) }
            put("status", run.status.wire)
            run.pauseReason?.let { put("pauseReason", it.wire) }
            run.terminalReason?.let { put("terminalReason", it.wire) }
            run.openedBy?.let { put("openedBy", it) }
            run.openedByKind?.let { put("openedByKind", it) }
            put("openedAt", run.openedAt.toString())
            run.closedBy?.let { put("closedBy", it) }
            run.closedAt?.let { put("closedAt", it.toString()) }
            run.coordinatorSeenAt?.let { put("coordinatorSeenAt", it.toString()) }
            put("coordinatorStalled", stalled(run, now))
            put("stallAfterSeconds", run.stallAfterSeconds)
            run.budget?.takeUnless { it.isUnlimited }?.let { put("budget", budget(it)) }
            put(
                "usage",
                buildJsonObject {
                    put("reportedTokens", run.reportedTokens)
                    put("reportedCostUsd", usd(run.reportedCostMicroUsd))
                    put("observedTokens", run.observedTokens)
                }
            )
            put("hints", JsonObject(run.hints.mapValues { JsonPrimitive(it.value) }))
            put("marks", JsonObject(run.marks.toSortedMap().mapValues { JsonPrimitive(it.value.toString()) }))
            put("members", members)
            put("activeSeats", activeSeats)
            put("version", run.version)
        }

    /** `snapshot`: what a close recorded. */
    fun snapshot(snapshot: RunEndedSnapshot): JsonObject =
        buildJsonObject {
            put("reason", snapshot.reason.wire)
            put("countsByRole", JsonObject(snapshot.countsByRole.mapValues { JsonPrimitive(it.value) }))
            put(
                "inFlight",
                buildJsonArray {
                    for (f in snapshot.inFlight) {
                        add(
                            buildJsonObject {
                                put("itemId", f.itemId.toString())
                                put("role", f.role)
                                put("seat", f.seat)
                            }
                        )
                    }
                }
            )
            put(
                "released",
                buildJsonObject {
                    put("claims", snapshot.released.claims)
                    put("leases", snapshot.released.leases)
                    put("seats", snapshot.released.seats)
                }
            )
            put("openDecisions", snapshot.openDecisions)
        }

    private fun stalled(
        run: Run,
        now: Instant
    ): Boolean {
        if (run.status == RunStatus.CLOSED) return false
        val seen = run.coordinatorSeenAt ?: return false
        return Duration.between(seen, now).seconds > run.stallAfterSeconds
    }

    private fun budget(budget: RunBudget): JsonObject =
        buildJsonObject {
            budget.tokens?.let { put("tokens", it) }
            budget.costMicroUsd?.let { put("costUsd", usd(it)) }
            budget.attemptsPerSeat?.let { put("attemptsPerSeat", it) }
            budget.maxActiveSeats?.let { put("maxActiveSeats", it) }
        }

    private fun usd(micro: Long): Double = micro / MICRO_PER_USD
}
