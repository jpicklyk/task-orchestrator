package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.application.port.StalledSeat
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/**
 * The stored form of a [RollupReport] (plan R28): the snapshot `run.ended` carries under `rollup`. It uses the wire member
 * names of the `report rollup` answer but FULL UUIDs and every typed field (the run's `rootId`, `window.clamped`), so
 * `decode(encode(r)) == r` for every report. A null section is left out and decodes back to null; an empty map stays an
 * empty object.
 */
object RollupSnapshotCodec {
    /** [report] as its stored JSON object. */
    fun encode(report: RollupReport): JsonObject =
        buildJsonObject {
            put(
                "scope",
                buildJsonObject {
                    report.scope.anchorId?.let { put("anchorId", it.toString()) }
                    report.scope.runId?.let { put("runId", it.toString()) }
                    put("rootId", report.scope.rootId.toString())
                }
            )
            put(
                "window",
                buildJsonObject {
                    put("since", report.window.since.toString())
                    put("until", report.window.until.toString())
                    put("clamped", report.window.clamped)
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
                        put("rootId", run.rootId.toString())
                    }
                )
            }
            put(
                "itemCounts",
                buildJsonObject {
                    put("total", report.items.total)
                    put("byRole", longs(report.items.byRole))
                }
            )
            put(
                "blocked",
                buildJsonObject {
                    put("total", report.blocked.total)
                    report.blocked.byReason?.let { put("byReason", ints(it)) }
                    report.blocked.resumable?.let { put("resumable", it) }
                    report.blocked.dwellMs?.let { put("dwellMs", longs(it)) }
                }
            )
            report.decisions?.let { decisions ->
                put(
                    "decisions",
                    buildJsonObject {
                        put("open", decisions.open)
                        put("byClass", ints(decisions.byClass))
                        put("bySeverity", ints(decisions.bySeverity))
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
                                            put("itemId", seat.itemId.toString())
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
                    }
                )
            }
        }

    /** The report stored as [text] (JSON), or null when it is not a well-formed stored rollup. */
    fun decode(text: String): RollupReport? =
        try {
            decode(Json.parseToJsonElement(text))
        } catch (_: IllegalArgumentException) {
            null
        }

    /** The report stored as [element], or null when it is not a well-formed stored rollup. */
    fun decode(element: JsonElement): RollupReport? =
        try {
            decodeOrThrow(element.jsonObject)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: java.time.format.DateTimeParseException) {
            null
        } catch (_: NoSuchElementException) {
            null
        }

    private fun decodeOrThrow(o: JsonObject): RollupReport {
        val scope = o.obj("scope")
        val window = o.obj("window")
        val items = o.obj("itemCounts")
        val blocked = o.obj("blocked")
        val transitions = o.obj("transitions")
        val cost = o.obj("cost")
        return RollupReport(
            scope =
                RollupScope(
                    anchorId = scope.optString("anchorId")?.let(UUID::fromString),
                    runId = scope.optString("runId")?.let(UUID::fromString),
                    rootId = UUID.fromString(scope.string("rootId"))
                ),
            window =
                RollupWindow(
                    since = Instant.parse(window.string("since")),
                    until = Instant.parse(window.string("until")),
                    clamped =
                        window
                            .getValue("clamped")
                            .jsonPrimitive.content
                            .toBooleanStrict()
                ),
            items = RollupItems(items.long("total"), items.obj("byRole").mapValues { it.value.jsonPrimitive.long }),
            blocked =
                RollupBlocked(
                    total = blocked.long("total"),
                    byReason = blocked.optObj("byReason")?.mapValues { it.value.jsonPrimitive.int },
                    resumable = blocked.optPrimitive("resumable")?.int,
                    dwellMs = blocked.optObj("dwellMs")?.mapValues { it.value.jsonPrimitive.long }
                ),
            transitions = RollupTransitions(transitions.long("total"), transitions.long("cascades"), transitions.long("rejected")),
            notesWritten = o.obj("notes").long("written"),
            cost =
                RollupCost(
                    calls = cost.long("calls"),
                    errors = cost.long("errors"),
                    retries = cost.long("retries"),
                    responseBytes = cost.long("responseBytes"),
                    responseTokensEst = cost.long("responseTokensEst"),
                    basis = cost.string("basis")
                ),
            run =
                o.optObj("run")?.let { run ->
                    RunHeader(
                        status = requireNotNull(RunStatus.fromWire(run.string("status"))) { "run.status" },
                        terminalReason =
                            run
                                .optString(
                                    "terminalReason"
                                )?.let { requireNotNull(RunTerminalReason.fromWire(it)) { "run.terminalReason" } },
                        openedAt = Instant.parse(run.string("openedAt")),
                        closedAt = run.optString("closedAt")?.let(Instant::parse),
                        rootId = UUID.fromString(run.string("rootId"))
                    )
                },
            decisions =
                o.optObj("decisions")?.let { d ->
                    DecisionCounts(
                        open = d.int("open"),
                        byClass = d.obj("byClass").mapValues { it.value.jsonPrimitive.int },
                        bySeverity = d.obj("bySeverity").mapValues { it.value.jsonPrimitive.int }
                    )
                },
            seats =
                o.optObj("seats")?.let { s ->
                    RollupSeats(
                        active = s.int("active"),
                        stalled =
                            s.getValue("stalled").jsonArray.map { e ->
                                val seat = e.jsonObject
                                StalledSeat(seat.string("seat"), UUID.fromString(seat.string("itemId")), seat.long("silentMs"))
                            },
                        count = s.int("count")
                    )
                },
            attempts = o.optObj("attempts")?.let { AttemptCounts(it.int("total"), it.int("max")) },
            budget =
                o.optObj("budget")?.let { b ->
                    BudgetFacts(used = b.long("used"), limit = b.optPrimitive("limit")?.long, basis = b.string("basis"))
                }
        )
    }

    private fun longs(map: Map<String, Long>): JsonObject = JsonObject(map.mapValues { JsonPrimitive(it.value) })

    private fun ints(map: Map<String, Int>): JsonObject = JsonObject(map.mapValues { JsonPrimitive(it.value) })

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.optObj(key: String): JsonObject? = this[key]?.takeUnless { it is JsonNull }?.jsonObject

    private fun JsonObject.optPrimitive(key: String): JsonPrimitive? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive

    private fun JsonObject.string(key: String): String {
        val p = getValue(key).jsonPrimitive
        require(p.isString) { "$key must be a string" }
        return p.content
    }

    private fun JsonObject.optString(key: String): String? =
        optPrimitive(key)?.let {
            if (it.isString) it.content else throw IllegalArgumentException(key)
        }

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
}
