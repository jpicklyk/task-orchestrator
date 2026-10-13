package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.application.report.ReportGroupBy
import io.github.jpicklyk.mcptask.current.application.report.ReportService
import io.github.jpicklyk.mcptask.current.application.report.ReportUsageRequest
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.PageWith
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `report usage`: call_log aggregates per group over a half-open window. Each item is
 * `{key{<groupBy>?: value}, calls, errors, responseBytes, responseTokensEst, avgLatencyMs, maxLatencyMs, retries, replays}`;
 * `data.window` follows `page`. A NULL group value omits the `<groupBy>` member, so `key` is `{}`; it is never JSON null.
 */
internal val reportUsageSelect =
    SelectSpec.PagedWith<ReportUsageRequest, JsonObject>(
        name = "usage",
        doc =
            "Call-log usage per group over [since, until): calls, errors, responseBytes, responseTokensEst (sums), " +
                "avgLatencyMs (rounded half up), maxLatencyMs, retries (attempts - 1) and replays. Ordered by calls desc, then " +
                "key asc. A missing group value is omitted from key ({}), never null, and sorts first. data.window gives " +
                "since, until, retainedSince (the oldest raw row held), basis and truncatedByRetention when the window " +
                "starts before the oldest data held. Basis raw reads the raw call log (window of at most 31 days inside the raw " +
                "horizon); otherwise basis daily reads per-day summaries (at most 400 days; since floors and a folded until ceils " +
                "to UTC midnight; groupBy principal is raw-only). A tag-scoped credential is forbidden; a root-scoped one sees only rows of its roots.",
        profiles = director(),
        request = serializer<ReportUsageRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req ->
            ReportService(env.unitOfWork, env.clock).usage(ctx, req).map { report ->
                val page = report.page
                PageWith(
                    ListPage(page.items.map { usageItem(report.groupBy, it) }, page.total, page.truncated, page.capped, page.next),
                    windowJson(report.window)
                )
            }
        },
        item = { it }
    )

/** One `usage` group on the wire; every metric is always present, and a NULL group value is an omitted `key` member. */
internal fun usageItem(
    groupBy: ReportGroupBy,
    row: UsageRow
): JsonObject =
    buildJsonObject {
        put("key", buildJsonObject { row.key?.let { put(groupBy.wire, it) } })
        put("calls", row.calls)
        put("errors", row.errors)
        put("responseBytes", row.responseBytes)
        put("responseTokensEst", row.responseTokensEst)
        put("avgLatencyMs", row.avgLatencyMs)
        put("maxLatencyMs", row.maxLatencyMs)
        put("retries", row.retries)
        put("replays", row.replays)
    }
