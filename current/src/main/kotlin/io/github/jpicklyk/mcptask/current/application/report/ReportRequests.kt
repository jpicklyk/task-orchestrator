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
    const val ROOT_ID =
        "Only this project root (depth 0): a UUID or a hex prefix of 8 to 32 characters. Default every root you may see."
    const val SINCE = "Window start, inclusive: ISO 8601 with Z or an offset. Default until minus 7 days."
    const val UNTIL =
        "Window end, exclusive: ISO 8601 with Z or an offset. Default now. At most 31 days after since (waste: 7)."
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
    @Doc(
        "Row grouping, default tool. usage accepts tool, operation, host, surface, principalKind, errorCode, day (UTC date), " +
            "principal (admin only)."
    )
    val groupBy: ReportGroupBy? = null,
    @Doc("Page size, 1 to 200 (default 50).") val limit: Int? = null,
    @Doc("The page.next value of the previous page.") val cursor: String? = null
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
