package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.report.ReportRollupRequest
import io.github.jpicklyk.mcptask.current.application.report.ReportService
import io.github.jpicklyk.mcptask.current.application.report.RollupReport
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ItemView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `report rollup` (plans R16 and R28): one object, no page, pointers and counts only (short ids, never note bodies).
 * `data` is `{scope, window, itemCounts, blocked, transitions, notes, cost}` on an anchor; a run rollup adds `run`,
 * `decisions`, `seats`, `attempts`, `budget` and `blocked.byReason` (plus `blocked.resumable` and `blocked.dwellMs` when
 * non-zero). A null section is omitted.
 */
internal val reportRollupSelect =
    SelectSpec.Single<ReportRollupRequest, RollupReport>(
        name = "rollup",
        doc =
            "Exactly one of anchorId and runId. anchorId: one subtree (the item and every descendant, any depth). " +
                "itemCounts: total and byRole (current state; lifecycle-free items not counted, zero roles omitted); " +
                "blocked.total (blocked role); transitions {total, cascades, rejected} and notes.written (note upserts; an " +
                "upsert of a since-deleted note is not counted) from subtree events in [since, until); cost {calls, errors, " +
                "retries, responseBytes, responseTokensEst, basis: targets}: the distinct calls in the window that named a " +
                "subtree item (target_ids, at most 50 ids per call) or wrote one of its events, a lower bound. Default window: " +
                "the first to the last subtree event (until = last + 1 ms), clamped to the last 31 days of that span " +
                "(window.clamped); the last 7 days when the subtree has none. An explicit window is at most 31 days (until " +
                "defaults to now, since to until minus 7 days). runId: the whole run (since and until are refused); window " +
                "is openedAt to closedAt or now. itemCounts and blocked cover the current members; blocked adds byReason, " +
                "resumable (awaiting_decision items whose decision no longer waits) and dwellMs (blocked time per reason in " +
                "the window); transitions and notes count the events stamped with the run, cost {basis: run} the calls " +
                "stamped with it (a lower bound). Adds run {status, terminalReason, openedAt, closedAt}, decisions {open, " +
                "byClass, bySeverity (overdue reads one step up)}, seats {active, stalled (at most 5, silent longest first), " +
                "count}, attempts {total, max} and budget {used, limit, basis: enforced} (tokens, from the run row). A " +
                "closed run answers the snapshot stored at close. resumable and dwellMs are omitted when zero or empty. " +
                "Ids are 8-character short ids. A tag-scoped credential is forbidden; a root-scoped one counts " +
                "only calls of its roots (anchorId) or sees only runs of its roots (runId).",
        profiles = director(),
        request = serializer<ReportRollupRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> ReportService(env.unitOfWork, env.clock).rollup(ctx, req) },
        data = { rollupJson(it) }
    )

/** `data` of a `rollup` answer, in a fixed member order; a null section is omitted. */
internal fun rollupJson(report: RollupReport): JsonObject =
    buildJsonObject {
        put(
            "scope",
            buildJsonObject {
                report.scope.anchorId?.let { put("anchorId", ItemView.shortId(it)) }
                report.scope.runId?.let { put("runId", ItemView.shortId(it)) }
                put("rootId", ItemView.shortId(report.scope.rootId))
            }
        )
        put(
            "window",
            buildJsonObject {
                put("since", report.window.since.toString())
                put("until", report.window.until.toString())
                if (report.window.clamped) put("clamped", true)
            }
        )
        report.run?.let { run ->
            put(
                "run",
                buildJsonObject {
                    put("status", run.status.wire)
                    run.terminalReason?.let { put("terminalReason", it.wire) }
                    put("openedAt", run.openedAt.toString())
                    run.closedAt?.let { put("closedAt", it.toString()) }
                }
            )
        }
        put(
            "itemCounts",
            buildJsonObject {
                put("total", report.items.total)
                put("byRole", buildJsonObject { report.items.byRole.forEach { (role, count) -> put(role, count) } })
            }
        )
        put(
            "blocked",
            buildJsonObject {
                put("total", report.blocked.total)
                report.blocked.byReason?.let { reasons ->
                    put("byReason", buildJsonObject { reasons.forEach { (reason, count) -> put(reason, count) } })
                }
                report.blocked.resumable
                    ?.takeIf { it > 0 }
                    ?.let { put("resumable", it) }
                report.blocked.dwellMs
                    ?.filterValues { it > 0 }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { dwell -> put("dwellMs", buildJsonObject { dwell.forEach { (reason, ms) -> put(reason, ms) } }) }
            }
        )
        report.decisions?.let { decisions ->
            put(
                "decisions",
                buildJsonObject {
                    put("open", decisions.open)
                    put("byClass", buildJsonObject { decisions.byClass.forEach { (k, v) -> put(k, v) } })
                    put("bySeverity", buildJsonObject { decisions.bySeverity.forEach { (k, v) -> put(k, v) } })
                }
            )
        }
        report.seats?.let { seats ->
            put(
                "seats",
                buildJsonObject {
                    put("active", seats.active)
                    put(
                        "stalled",
                        buildJsonArray {
                            seats.stalled.forEach { seat ->
                                add(
                                    buildJsonObject {
                                        put("seat", seat.seat)
                                        put("itemId", ItemView.shortId(seat.itemId))
                                        put("silentMs", seat.silentMs)
                                    }
                                )
                            }
                        }
                    )
                    put("count", seats.count)
                }
            )
        }
        put(
            "transitions",
            buildJsonObject {
                put("total", report.transitions.total)
                put("cascades", report.transitions.cascades)
                put("rejected", report.transitions.rejected)
            }
        )
        put("notes", buildJsonObject { put("written", report.notesWritten) })
        put(
            "cost",
            buildJsonObject {
                put("calls", report.cost.calls)
                put("errors", report.cost.errors)
                put("retries", report.cost.retries)
                put("responseBytes", report.cost.responseBytes)
                put("responseTokensEst", report.cost.responseTokensEst)
                put("basis", report.cost.basis)
            }
        )
        report.attempts?.let { attempts ->
            put(
                "attempts",
                buildJsonObject {
                    put("total", attempts.total)
                    put("max", attempts.max)
                }
            )
        }
        report.budget?.let { budget ->
            put(
                "budget",
                buildJsonObject {
                    put("used", budget.used)
                    budget.limit?.let { put("limit", it) }
                    put("basis", budget.basis)
                    if (budget.breaches != 0) put("breaches", budget.breaches)
                    if (budget.extends != 0) put("extends", budget.extends)
                }
            )
        }
    }
