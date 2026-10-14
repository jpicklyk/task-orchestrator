package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.report.ReportService
import io.github.jpicklyk.mcptask.current.application.report.ReportWasteRequest
import io.github.jpicklyk.mcptask.current.application.report.WasteReport
import io.github.jpicklyk.mcptask.current.application.report.WasteSignal
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `report waste` (plan R12): one object, no page. `data` is `{window, signals}`; every signal is always present as
 * `{value?, numerator, denominator, basis?}`, and `relatedFollowThrough` always holds `edges`, `links`, `tags`, `text`.
 */
internal val reportWasteSelect =
    SelectSpec.Single<ReportWasteRequest, WasteReport>(
        name = "waste",
        doc =
            "Waste signals over [since, until), default the 7 days before now, " +
                "at most 7 days, each {value?, numerator, denominator, basis?}: value is " +
                "numerator/denominator rounded half up to 4 decimals, omitted when denominator is 0. redundantReads, " +
                "readsWithoutLaterWrite and relatedFollowThrough (edges, links, tags, text) use MCP session rows only " +
                "(basis mcp_sessions); overFetch, emptyNext and lifecycleFreeRejections use both surfaces. data.window as " +
                "usage. A tag-scoped credential is forbidden; a root-scoped one sees only rows of its roots.",
        profiles = director(),
        request = serializer<ReportWasteRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> ReportService(env.unitOfWork, env.clock).waste(ctx, req) },
        data = { wasteJson(it) }
    )

/** `data` of a `waste` answer: `window` (as `usage`) then `signals`, in a fixed member order. */
internal fun wasteJson(report: WasteReport): JsonObject =
    buildJsonObject {
        windowJson(report.window).forEach { (key, value) -> put(key, value) }
        put(
            "signals",
            buildJsonObject {
                put("redundantReads", signalJson(report.redundantReads))
                put("overFetch", signalJson(report.overFetch))
                put("readsWithoutLaterWrite", signalJson(report.readsWithoutLaterWrite))
                put("emptyNext", signalJson(report.emptyNext))
                put(
                    "relatedFollowThrough",
                    buildJsonObject { report.relatedFollowThrough.forEach { (signal, value) -> put(signal, signalJson(value)) } }
                )
                put("lifecycleFreeRejections", signalJson(report.lifecycleFreeRejections))
            }
        )
    }

/** One signal: `value` (a JSON number, omitted when the denominator is 0), `numerator`, `denominator`, `basis` when set. */
internal fun signalJson(signal: WasteSignal): JsonObject =
    buildJsonObject {
        signal.value?.let { put("value", it.toDouble()) }
        put("numerator", signal.numerator)
        put("denominator", signal.denominator)
        signal.basis?.let { put("basis", it) }
    }
