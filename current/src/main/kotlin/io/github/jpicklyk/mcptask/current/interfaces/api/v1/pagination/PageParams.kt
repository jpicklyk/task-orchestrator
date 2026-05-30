package io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.PageDto
import io.ktor.server.application.ApplicationCall

/** Default page number when the `page` query parameter is absent or invalid. */
const val DEFAULT_PAGE = 1

/** Default page size when the `pageSize` query parameter is absent or invalid. */
const val DEFAULT_PAGE_SIZE = 50

/** Maximum allowed page size — requests above this are capped silently. */
const val MAX_PAGE_SIZE = 200

/**
 * Parsed, validated pagination parameters for a list endpoint.
 *
 * @property page 1-based page number (minimum 1).
 * @property pageSize Number of items per page (clamped to [1, MAX_PAGE_SIZE]).
 */
data class PageParams(
    val page: Int,
    val pageSize: Int,
) {
    /** Zero-based offset for SQL `LIMIT`/`OFFSET` queries. */
    val offset: Int get() = (page - 1) * pageSize
}

/**
 * Parses `page` and `pageSize` query parameters from the current request.
 *
 * - Missing / non-integer `page` → [DEFAULT_PAGE].
 * - `page` < 1 → clamped to 1.
 * - Missing / non-integer `pageSize` → [DEFAULT_PAGE_SIZE].
 * - `pageSize` > [MAX_PAGE_SIZE] → clamped to [MAX_PAGE_SIZE].
 * - `pageSize` < 1 → clamped to 1.
 */
fun ApplicationCall.pageParams(): PageParams {
    val rawPage = request.queryParameters["page"]?.toIntOrNull() ?: DEFAULT_PAGE
    val rawSize = request.queryParameters["pageSize"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE
    return PageParams(
        page = maxOf(1, rawPage),
        pageSize = minOf(MAX_PAGE_SIZE, maxOf(1, rawSize)),
    )
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
 */
fun <T> buildPageDto(
    items: List<T>,
    pageParams: PageParams,
    totalItems: Long?,
): PageDto<T> {
    val hasMore =
        if (totalItems != null) {
            (pageParams.offset + items.size).toLong() < totalItems
        } else {
            items.size >= pageParams.pageSize
        }
    return PageDto(
        items = items,
        page = pageParams.page,
        pageSize = pageParams.pageSize,
        totalItems = totalItems,
        hasMore = hasMore,
    )
}
