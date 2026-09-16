package io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.PageDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/** Default page number when the `page` query parameter is absent or blank. */
const val DEFAULT_PAGE = 1

/** Default page size when the `pageSize` query parameter is absent or blank. */
const val DEFAULT_PAGE_SIZE = 50

/** Maximum allowed page size — requests above this are capped silently. */
const val MAX_PAGE_SIZE = 200

/**
 * Maximum allowed page number — requests above this are rejected with `400 validation_error`
 * rather than silently served or left to overflow downstream arithmetic. At
 * `(MAX_PAGE - 1) * MAX_PAGE_SIZE` the largest possible offset (`19_999_800`) stays an order of
 * magnitude inside `Int` range.
 */
const val MAX_PAGE = 100_000

/**
 * Parsed, validated pagination parameters for a list endpoint.
 *
 * @property page 1-based page number (minimum 1, maximum [MAX_PAGE]).
 * @property pageSize Number of items per page (clamped to [1, MAX_PAGE_SIZE]).
 */
data class PageParams(
    val page: Int,
    val pageSize: Int,
) {
    /**
     * Zero-based offset for SQL `LIMIT`/`OFFSET` queries. Always non-negative for any
     * `page >= 1, pageSize >= 1` — the multiply is carried out in `Long` and saturated at
     * `Int.MAX_VALUE` rather than wrapping, so a [PageParams] constructed directly (bypassing
     * [parsePageParams]'s bounds, as some tests do) can never yield a negative offset.
     */
    val offset: Int
        get() = ((page - 1).toLong() * pageSize.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Result of parsing `page`/`pageSize` query parameters from a request via [parsePageParams].
 */
sealed interface PageParamsResult {
    data class Valid(
        val params: PageParams
    ) : PageParamsResult

    data class Invalid(
        val message: String
    ) : PageParamsResult
}

/**
 * Parses and validates `page` and `pageSize` query parameters from the current request.
 *
 * - Missing or blank `page` → [DEFAULT_PAGE].
 * - `page` non-integer (including digit strings too large for `Int`), `< 1`, or `> [MAX_PAGE]`
 *   → [PageParamsResult.Invalid]. Never silently clamped or defaulted — a request for
 *   `page=99999999999` previously fell through `toIntOrNull()` and was served as page 1 with a
 *   200, which is the defect this type exists to close.
 * - Missing or blank `pageSize` → [DEFAULT_PAGE_SIZE].
 * - `pageSize` non-integer or `< 1` → [PageParamsResult.Invalid].
 * - `pageSize > [MAX_PAGE_SIZE]` → silently capped at [MAX_PAGE_SIZE] (unchanged, documented
 *   behaviour).
 */
fun ApplicationCall.parsePageParams(): PageParamsResult {
    val rawPage = request.queryParameters["page"]?.takeIf { it.isNotBlank() }
    val page =
        if (rawPage == null) {
            DEFAULT_PAGE
        } else {
            val parsed =
                rawPage.toIntOrNull()
                    ?: return PageParamsResult.Invalid("page must be an integer between 1 and $MAX_PAGE")
            if (parsed < 1 || parsed > MAX_PAGE) {
                return PageParamsResult.Invalid("page must be an integer between 1 and $MAX_PAGE")
            }
            parsed
        }

    val rawPageSize = request.queryParameters["pageSize"]?.takeIf { it.isNotBlank() }
    val pageSize =
        if (rawPageSize == null) {
            DEFAULT_PAGE_SIZE
        } else {
            val parsed =
                rawPageSize.toIntOrNull()
                    ?: return PageParamsResult.Invalid("pageSize must be a positive integer")
            if (parsed < 1) {
                return PageParamsResult.Invalid("pageSize must be a positive integer")
            }
            minOf(MAX_PAGE_SIZE, parsed)
        }

    return PageParamsResult.Valid(PageParams(page = page, pageSize = pageSize))
}

/**
 * Parses `page`/`pageSize` query parameters from the current request, responding
 * `400 validation_error` and returning `null` when invalid.
 *
 * Callers: `val pp = call.pageParamsOrRespond() ?: return@get`.
 */
suspend fun ApplicationCall.pageParamsOrRespond(): PageParams? =
    when (val result = parsePageParams()) {
        is PageParamsResult.Valid -> result.params
        is PageParamsResult.Invalid -> {
            respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", result.message))
            null
        }
    }

/**
 * Builds a [PageDto] from a list of items, pagination parameters, and an optional total count.
 *
 * `hasMore` is computed from the number of items returned relative to [pageParams.pageSize].
 * When [totalItems] is null (expensive to compute), `hasMore` is inferred from whether
 * `items.size == pageParams.pageSize` — this may produce a false-positive on the last page
 * when the final page is exactly full, but is acceptable for v1.
 *
 * @param items The items on this page (already limited to [PageParams.pageSize] rows).
 * @param pageParams The parsed page/pageSize pair.
 * @param totalItems Optional exact total count; null when too expensive to compute.
 * @param skipped Optional count of rows dropped from this page's window due to failed domain
 *   validation (see [io.github.jpicklyk.mcptask.current.domain.repository.ItemFetchResult]).
 *   Pass null or 0 when nothing was skipped — either way it is omitted from the serialized DTO.
 */
fun <T> buildPageDto(
    items: List<T>,
    pageParams: PageParams,
    totalItems: Long?,
    skipped: Int? = null,
): PageDto<T> {
    val hasMore =
        if (totalItems != null) {
            pageParams.offset.toLong() + items.size.toLong() < totalItems
        } else {
            items.size >= pageParams.pageSize
        }
    return PageDto(
        items = items,
        page = pageParams.page,
        pageSize = pageParams.pageSize,
        totalItems = totalItems,
        hasMore = hasMore,
        skipped = skipped?.takeIf { it > 0 },
    )
}
