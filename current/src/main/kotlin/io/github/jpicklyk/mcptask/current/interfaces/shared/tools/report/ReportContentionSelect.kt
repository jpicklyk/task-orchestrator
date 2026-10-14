package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.port.ContentionRow
import io.github.jpicklyk.mcptask.current.application.report.ReportContentionRequest
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
 * `report contention`: claim and lease aggregates per (kind, resource) over a half-open window. Each item is
 * `{key{resource?}, kind, acquired, rejected, expired, released, forceReleased, contentionRate?, expiryRate?,
 * forceReleaseRate?}`; `data.window` follows `page`.
 */
internal val reportContentionSelect =
    SelectSpec.PagedWith<ReportContentionRequest, JsonObject>(
        name = "contention",
        doc =
            "Claim and lease contention per resource over [since, until) (default the 7 days before now, " +
                "at most 31 days): kind (claim or lease), acquired, rejected, expired, " +
                "released and forceReleased counts, plus contentionRate (rejected / (acquired + rejected)), expiryRate " +
                "(expired / acquired) and forceReleaseRate (forceReleased / acquired), 4 decimals half up, omitted when the " +
                "denominator is 0 and never clamped (a window edge can push a rate above 1). A lease's resource is its key " +
                "(a lease.rejected counts once per contended key); a claim's resource is the claimed item id. Only leases " +
                "report forceReleased. groupBy resource (the only value). Ordered by rejected desc, then resource asc, then " +
                "kind asc. data.window gives since, until, retainedSince (the oldest claim or lease event held), basis raw " +
                "and truncatedByRetention. Visibility as for gates.",
        profiles = director(),
        request = serializer<ReportContentionRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req ->
            ReportService(env.unitOfWork, env.clock).contention(ctx, req).map { report ->
                val page = report.page
                PageWith(
                    ListPage(page.items.map(::contentionItem), page.total, page.truncated, page.capped, page.next),
                    windowJson(report.window)
                )
            }
        },
        item = { it }
    )

/** One `contention` group on the wire; counts are always present, a rate only when its denominator is above 0. */
internal fun contentionItem(row: ContentionRow): JsonObject =
    buildJsonObject {
        put("key", buildJsonObject { row.resource?.let { put("resource", it) } })
        put("kind", row.kind.wire)
        put("acquired", row.acquired)
        put("rejected", row.rejected)
        put("expired", row.expired)
        put("released", row.released)
        put("forceReleased", row.forceReleased)
        row.contentionRate?.let { put("contentionRate", it) }
        row.expiryRate?.let { put("expiryRate", it) }
        row.forceReleaseRate?.let { put("forceReleaseRate", it) }
    }
