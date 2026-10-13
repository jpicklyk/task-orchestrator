package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.report.ReportWindow
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val REPORT = "report"

/**
 * The `report` tool (plan R9): read-only aggregate reports, one file per select. Director-only on MCP (profiles
 * {DIRECTOR}); REST serves it at `POST /api/v1/ops/report`. R9 ships `usage`, R11 `gates` and `contention`; rollup and waste follow.
 */
fun reportTool(): SelectToolSpec =
    SelectToolSpec(
        name = REPORT,
        description =
            "Aggregate reports. select: usage (call-log calls, errors, bytes, tokens, latency per group), gates (rejected " +
                "transitions), contention (claims, leases).",
        selects = listOf(reportUsageSelect, reportGatesSelect, reportContentionSelect)
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
