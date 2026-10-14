package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.report.ReportWindow
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val REPORT = "report"

/**
 * The `report` tool (plan R9): read-only aggregate reports, one file per select. Director-only on MCP (profiles
 * {DIRECTOR}); REST serves it at `POST /api/v1/ops/report`. R9 ships `usage`, R11 `gates` and `contention`, R12 `waste`,
 * R16 the anchor `rollup`; R28 adds the run rollup.
 */
fun reportTool(): SelectToolSpec =
    SelectToolSpec(
        name = REPORT,
        description =
            "Aggregate reports. select: usage (call metrics per group), gates (rejected transitions), contention (claims, " +
                "leases), waste (ratios), rollup (subtree items, transitions, notes, cost).",
        selects = listOf(reportUsageSelect, reportGatesSelect, reportContentionSelect, reportWasteSelect, reportRollupSelect)
    )

/** `data.window`: `{since, until, retainedSince?, basis, truncatedByRetention?}`, instants in UTC. */
internal fun windowJson(window: ReportWindow): JsonObject =
    buildJsonObject {
        put(
            "window",
            buildJsonObject {
                put("since", window.since.toString())
                put("until", window.until.toString())
                window.retainedSince?.let { put("retainedSince", it.toString()) }
                put("basis", window.basis)
                if (window.truncatedByRetention) put("truncatedByRetention", true)
            }
        )
    }
