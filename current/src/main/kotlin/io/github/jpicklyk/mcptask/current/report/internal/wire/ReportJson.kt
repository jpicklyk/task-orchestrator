package io.github.jpicklyk.mcptask.current.report.internal.wire

import io.github.jpicklyk.mcptask.current.report.internal.app.ReportWindow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
