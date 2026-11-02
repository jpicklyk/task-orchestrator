package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.port.ListPage
import io.github.jpicklyk.mcptask.current.application.port.NoteWriteRow
import io.github.jpicklyk.mcptask.current.application.report.ReportGroupBy
import io.github.jpicklyk.mcptask.current.application.report.ReportNotesRequest
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
 * `report notes`: note.upserted aggregates per group over a half-open window. Each item is
 * `{key{<groupBy>?: value}, writes, notes, rewrites, bytes, maxBytes, avgBytes}`; `data.window` follows `page`. A write
 * with no value for the dimension falls in the `{}` group.
 */
internal val reportNotesSelect =
    SelectSpec.PagedWith<ReportNotesRequest, JsonObject>(
        name = "notes",
        doc =
            "Note writes per group over [since, until) (default the 7 days before now, at most 31 days): writes " +
                "(note.upserted events), notes (distinct notes), rewrites (writes minus notes), bytes, maxBytes, avgBytes " +
                "(whole bytes per write). groupBy role (default), noteKey, seat, type (the item's current type), " +
                "principalKind or day; a write with no value (no seat, a deleted item) falls in the {} group. " +
                "Ordered by bytes desc, then key asc. anchorId keeps one subtree and combines with rootId by AND. " +
                "data.window gives since, until, retainedSince (the oldest note.upserted event held), basis raw and " +
                "truncatedByRetention. A root-scoped credential sees its roots; a tag-scoped one sees writes on items " +
                "that currently carry an allowed tag.",
        profiles = director(),
        request = serializer<ReportNotesRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req ->
            ReportService(env.unitOfWork, env.clock).notes(ctx, req).map { report ->
                val page = report.page
                PageWith(
                    ListPage(page.items.map { noteItem(report.groupBy, it) }, page.total, page.truncated, page.capped, page.next),
                    windowJson(report.window)
                )
            }
        },
        item = { it }
    )

/** One `notes` group on the wire; a NULL group value is an omitted `key` member. rewrites and avgBytes are derived here. */
internal fun noteItem(
    groupBy: ReportGroupBy,
    row: NoteWriteRow
): JsonObject =
    buildJsonObject {
        put("key", buildJsonObject { row.key?.let { put(groupBy.wire, it) } })
        put("writes", row.writes)
        put("notes", row.notes)
        put("rewrites", row.writes - row.notes)
        put("bytes", row.bytes)
        put("maxBytes", row.maxBytes)
        put("avgBytes", if (row.writes > 0) row.bytes / row.writes else 0L)
    }
