package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.UsageDimension
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `report` `groupBy` values, one enum for every select (plan R9). Each select accepts a documented subset; a value
 * outside it is `invalid_request` on `/groupBy`. [dimension] is the `usage` column, null for a value `usage` rejects.
 * [PRINCIPAL] is admin-only (W6 decision 10).
 */
@Serializable
enum class ReportGroupBy(
    val wire: String,
    val dimension: UsageDimension?
) {
    @SerialName("tool")
    TOOL("tool", UsageDimension.TOOL),

    @SerialName("operation")
    OPERATION("operation", UsageDimension.OPERATION),

    @SerialName("host")
    HOST("host", UsageDimension.HOST),

    @SerialName("surface")
    SURFACE("surface", UsageDimension.SURFACE),

    @SerialName("principalKind")
    PRINCIPAL_KIND("principalKind", UsageDimension.PRINCIPAL_KIND),

    @SerialName("errorCode")
    ERROR_CODE("errorCode", UsageDimension.ERROR_CODE),

    @SerialName("day")
    DAY("day", UsageDimension.DAY),

    @SerialName("principal")
    PRINCIPAL("principal", UsageDimension.PRINCIPAL),

    @SerialName("noteKey")
    NOTE_KEY("noteKey", null),

    @SerialName("type")
    TYPE("type", null),

    @SerialName("trait")
    TRAIT("trait", null),

    @SerialName("resource")
    RESOURCE("resource", null)
}

/**
 * The `@Doc` text of the fields every windowed select shares. The tool's input schema is one union of fields, so a field
 * must carry the same schema (and therefore the same description) in every select that declares it (`ToolRegistry`).
 */
internal object ReportFieldDocs {
    const val ROOT_ID = "Only this depth-0 root (UUID or 8-32 hex prefix). Default: all you may see."
    const val SINCE = "Window start, inclusive: ISO 8601 with Z or offset. Default and cap: describe."
    const val UNTIL = "Window end, exclusive: ISO 8601 with Z or offset."
}

/** `report usage`: call_log aggregates over a half-open window, grouped by one column. */
@Serializable
data class ReportUsageRequest(
    @Doc(ReportFieldDocs.ROOT_ID)
    val rootId: IdRef? = null,
    @Doc(ReportFieldDocs.SINCE)
    val since: String? = null,
    @Doc(ReportFieldDocs.UNTIL)
    val until: String? = null,
    @Doc(GROUP_BY_DOC)
    val groupBy: ReportGroupBy? = null,
    @Doc("Page size, 1 to 200 (default 50).") val limit: Int? = null,
    @Doc(CURSOR_DOC) val cursor: String? = null
)

/** `report gates`: transition.rejected aggregates over a half-open window. The fields and docs match [ReportUsageRequest] (one name, one schema). */
@Serializable
data class ReportGatesRequest(
    @Doc(ReportFieldDocs.ROOT_ID)
    val rootId: IdRef? = null,
    @Doc(ReportFieldDocs.SINCE)
    val since: String? = null,
    @Doc(ReportFieldDocs.UNTIL)
    val until: String? = null,
    @Doc(GROUP_BY_DOC)
    val groupBy: ReportGroupBy? = null,
    @Doc("Page size, 1 to 200 (default 50).") val limit: Int? = null,
    @Doc(CURSOR_DOC) val cursor: String? = null
)

/** `report contention`: claim and lease aggregates over a half-open window. The fields and docs match [ReportUsageRequest]. */
@Serializable
data class ReportContentionRequest(
    @Doc(ReportFieldDocs.ROOT_ID)
    val rootId: IdRef? = null,
    @Doc(ReportFieldDocs.SINCE)
    val since: String? = null,
    @Doc(ReportFieldDocs.UNTIL)
    val until: String? = null,
    @Doc(GROUP_BY_DOC)
    val groupBy: ReportGroupBy? = null,
    @Doc("Page size, 1 to 200 (default 50).") val limit: Int? = null,
    @Doc(CURSOR_DOC) val cursor: String? = null
)

/** `report waste`: waste signals over a half-open window of at most 7 days; one object, no grouping and no page. */
@Serializable
data class ReportWasteRequest(
    @Doc(ReportFieldDocs.ROOT_ID)
    val rootId: IdRef? = null,
    @Doc(ReportFieldDocs.SINCE)
    val since: String? = null,
    @Doc(ReportFieldDocs.UNTIL)
    val until: String? = null
)

/**
 * `report rollup` over one subtree ([anchorId], plan R16) or one run ([runId], R28): exactly one of them (both absent is
 * `invalid_request` on `/anchorId`, both given on `/runId`). A run rollup always covers the whole run, so `since` and
 * `until` are refused with [runId]. One object, no grouping and no page. [runId] is the last parameter so R16's
 * positional calls keep compiling.
 */
@Serializable
data class ReportRollupRequest(
    @Doc("Roll up this subtree (UUID or 8-32 hex prefix).")
    val anchorId: IdRef? = null,
    @Doc(ReportFieldDocs.SINCE)
    val since: String? = null,
    @Doc(ReportFieldDocs.UNTIL)
    val until: String? = null,
    @Doc("Roll up this run (UUID or 8-32 hex prefix).")
    val runId: IdRef? = null
)

private const val GROUP_BY_DOC = "Row grouping; subset and default per select."

private const val CURSOR_DOC = "page.next of the last page."
