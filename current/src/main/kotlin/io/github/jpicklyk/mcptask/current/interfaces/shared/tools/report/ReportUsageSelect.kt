package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.application.report.ReportGroupBy
import io.github.jpicklyk.mcptask.current.application.report.ReportUsageRequest
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.PageWith
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `report usage`: call_log aggregates per group over a half-open window. Each item is
 * `{key{<groupBy>: value|null}, calls, errors, responseBytes, responseTokensEst, avgLatencyMs, maxLatencyMs, retries, replays}`;
 * `data.window` follows `page`.
 */
internal val reportUsageSelect =
    SelectSpec.PagedWith<ReportUsageRequest, JsonObject>(
        name = "usage",
        doc =
            "Call-log usage per group over [since, until): calls, errors, responseBytes, responseTokensEst (sums), " +
                "avgLatencyMs (rounded half up), maxLatencyMs, retries (attempts - 1) and replays. Ordered by calls desc, then " +
                "key asc (a null key first). data.window gives since, until, retainedSince (the oldest raw row held), basis " +
                "raw and truncatedByRetention when the window starts before it. A tag-scoped credential is forbidden; a " +
                "root-scoped one sees only rows of its roots.",
        profiles = director(),
        request = serializer<ReportUsageRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req ->
            env.reportService.usage(ctx, req).map { report ->
                val page = report.page
                PageWith(
                    ListPage(page.items.map { usageItem(report.groupBy, it) }, page.total, page.truncated, page.capped, page.next),
                    windowJson(report.window)
                )
            }
        },
        item = { it }
    )

/** One `usage` group on the wire; every metric is always present. */
internal fun usageItem(
    groupBy: ReportGroupBy,
    row: UsageRow
): JsonObject =
    buildJsonObject {
        put("key", buildJsonObject { put(groupBy.wire, row.key?.let { JsonPrimitive(it) } ?: JsonNull) })
        put("calls", row.calls)
        put("errors", row.errors)
        put("responseBytes", row.responseBytes)
        put("responseTokensEst", row.responseTokensEst)
        put("avgLatencyMs", row.avgLatencyMs)
        put("maxLatencyMs", row.maxLatencyMs)
        put("retries", row.retries)
        put("replays", row.replays)
    }
