package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.port.GateRow
import io.github.jpicklyk.mcptask.current.application.report.ReportGatesRequest
import io.github.jpicklyk.mcptask.current.application.report.ReportGroupBy
import io.github.jpicklyk.mcptask.current.application.report.ReportService
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.PageWith
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `report gates`: transition.rejected aggregates per group over a half-open window. Each item is
 * `{key{<groupBy>?: value}, blocks, items}`; `data.window` follows `page`. A rejection with no value for the dimension
 * falls in the `{}` group.
 */
internal val reportGatesSelect =
    SelectSpec.PagedWith<ReportGatesRequest, JsonObject>(
        name = "gates",
        doc =
            "Rejected transitions per group over [since, until) (default the 7 days before now, " +
                "at most 31 days): blocks (distinct rejections) and items (distinct item ids). " +
                "groupBy noteKey (default, one count per missing note key), type or trait count only gate_blocked rejections, " +
                "joined to the item's current type and traits (as of now, not as of the rejection); errorCode counts every " +
                "rejection code. A rejection with no value for the dimension (no missing key, a deleted item, no type or trait) " +
                "falls in the {} group. Ordered by blocks desc, then key asc. data.window gives since, until, retainedSince " +
                "(the oldest transition.rejected event held), basis raw and truncatedByRetention. A root-scoped credential sees " +
                "its roots; a tag-scoped one sees rejections of items that currently carry an allowed tag.",
        profiles = director(),
        request = serializer<ReportGatesRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req ->
            ReportService(env.unitOfWork, env.clock).gates(ctx, req).map { report ->
                val page = report.page
                PageWith(
                    ListPage(page.items.map { gateItem(report.groupBy, it) }, page.total, page.truncated, page.capped, page.next),
                    windowJson(report.window)
                )
            }
        },
        item = { it }
    )

/** One `gates` group on the wire; a NULL group value is an omitted `key` member. */
internal fun gateItem(
    groupBy: ReportGroupBy,
    row: GateRow
): JsonObject =
    buildJsonObject {
        put("key", buildJsonObject { row.key?.let { put(groupBy.wire, it) } })
        put("blocks", row.blocks)
        put("items", row.items)
    }
