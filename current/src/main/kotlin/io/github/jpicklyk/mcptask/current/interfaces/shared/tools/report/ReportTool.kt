package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.report.ReportWindow
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val REPORT = "report"

/**
 * The `report` tool (plan R9): read-only aggregate reports, one file per select. Director-only on MCP (profiles
 * {DIRECTOR}); REST serves it at `POST /api/v1/ops/report`. R9 ships `usage`, R12 `waste`; rollup, gates and contention
 * follow.
 */
fun reportTool(): SelectToolSpec =
    SelectToolSpec(
        name = REPORT,
        description =
            "Aggregate reports over the call log. select: usage (calls, errors, bytes, tokens, latency, retries and replays " +
                "per group); waste (ratios: redundant reads, over-fetch, reads with no later write, empty next, related " +
                "follow-through, lifecycle-free rejections).",
        selects = listOf(reportUsageSelect, reportWasteSelect)
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
