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

/** `report usage`: call_log aggregates over a half-open window, grouped by one column. */
@Serializable
data class ReportUsageRequest(
    @Doc("Only this project root (depth 0): a UUID or a hex prefix of 8 to 32 characters. Default every root you may see.")
    val rootId: IdRef? = null,
    @Doc("Window start, inclusive: ISO 8601 with Z or an offset. Default until minus 7 days.")
    val since: String? = null,
    @Doc("Window end, exclusive: ISO 8601 with Z or an offset. Default now. At most 31 days after since.")
    val until: String? = null,
    @Doc(GROUP_BY_DOC)
    val groupBy: ReportGroupBy? = null,
    @Doc("Page size, 1 to 200 (default 50).") val limit: Int? = null,
    @Doc("The page.next value of the previous page.") val cursor: String? = null
)

/** `report gates`: transition.rejected aggregates over a half-open window. The fields and docs match [ReportUsageRequest] (one name, one schema). */
@Serializable
data class ReportGatesRequest(
    @Doc("Only this project root (depth 0): a UUID or a hex prefix of 8 to 32 characters. Default every root you may see.")
    val rootId: IdRef? = null,
    @Doc("Window start, inclusive: ISO 8601 with Z or an offset. Default until minus 7 days.")
    val since: String? = null,
    @Doc("Window end, exclusive: ISO 8601 with Z or an offset. Default now. At most 31 days after since.")
    val until: String? = null,
    @Doc(GROUP_BY_DOC)
    val groupBy: ReportGroupBy? = null,
    @Doc("Page size, 1 to 200 (default 50).") val limit: Int? = null,
    @Doc("The page.next value of the previous page.") val cursor: String? = null
)

/** `report contention`: claim and lease aggregates over a half-open window. The fields and docs match [ReportUsageRequest]. */
@Serializable
data class ReportContentionRequest(
    @Doc("Only this project root (depth 0): a UUID or a hex prefix of 8 to 32 characters. Default every root you may see.")
    val rootId: IdRef? = null,
    @Doc("Window start, inclusive: ISO 8601 with Z or an offset. Default until minus 7 days.")
    val since: String? = null,
    @Doc("Window end, exclusive: ISO 8601 with Z or an offset. Default now. At most 31 days after since.")
    val until: String? = null,
    @Doc(GROUP_BY_DOC)
    val groupBy: ReportGroupBy? = null,
    @Doc("Page size, 1 to 200 (default 50).") val limit: Int? = null,
    @Doc("The page.next value of the previous page.") val cursor: String? = null
)

private const val GROUP_BY_DOC = "Row grouping; each select accepts its own subset and default, see describe."
