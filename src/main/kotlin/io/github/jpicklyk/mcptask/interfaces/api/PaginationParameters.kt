package io.github.jpicklyk.mcptask.interfaces.api

import kotlinx.serialization.Serializable

/**
 * Standard pagination parameters used across API endpoints.
 */
@Serializable
data class PaginationParameters(
    val page: Int = 1,
    val pageSize: Int = 20,
    val sortBy: String? = null,
    val sortDirection: SortDirection = SortDirection.ASC
) {
    companion object {
        const val DEFAULT_PAGE = 1
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }

    /**
     * Validates and normalizes pagination parameters.
     * @return Corrected pagination parameters
     */
    fun validated(): PaginationParameters {
        val normalizedPage = if (page < 1) DEFAULT_PAGE else page
        val normalizedPageSize = when {
            pageSize < 1 -> DEFAULT_PAGE_SIZE
            pageSize > MAX_PAGE_SIZE -> MAX_PAGE_SIZE
            else -> pageSize
        }

        return copy(page = normalizedPage, pageSize = normalizedPageSize)
    }

    /**
     * Calculates the offset for database queries.
     */
    fun getOffset(): Int = (page - 1) * pageSize
}

/**
 * Sort direction options for API requests.
 */
@Serializable
enum class SortDirection {
    ASC, DESC;

    /**
     * Converts to database-friendly sort string.
     */
    fun toSqlSortDirection(): String = when (this) {
        ASC -> "ASC"
        DESC -> "DESC"
    }
}
