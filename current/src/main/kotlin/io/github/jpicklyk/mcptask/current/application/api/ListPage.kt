package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome

/**
 * One page of a list read (envelope `data.items` + `data.page`).
 *
 * [total] is the full count before the cap; [truncated] is true exactly when `items.size < total`;
 * [next] is an opaque cursor, present exactly when [truncated]. When [capped] is true the engine
 * capped the result, so [total] is a lower bound rather than an exact count; [truncated] and [next]
 * keep their meaning.
 */
data class ListPage<out T>(
    val items: List<T>,
    val total: Int,
    val truncated: Boolean,
    val capped: Boolean = false,
    val next: String? = null
) {
    init {
        require(total >= 0) { "total must not be negative" }
        require(items.size <= total) { "items.size (${items.size}) must not exceed total ($total)" }
        require(truncated == (items.size < total)) { "truncated must equal items.size < total" }
        require((next != null) == truncated) { "next must be present exactly when truncated" }
    }
}

/** A validated page request: [limit] in `1..MAX_LIMIT`, [offset] non-negative. */
data class PageRequest(
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT" }
        require(offset >= 0) { "offset must not be negative" }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200

        /**
         * Builds a request from the raw `limit` and `cursor` parameters: a null [limit] is
         * [DEFAULT_LIMIT]; a limit outside `1..MAX_LIMIT` is `invalid_request` on field `limit`;
         * a [cursor] is decoded by [CursorCodec] (a bad one is `invalid_cursor`), a null one is offset 0.
         */
        fun from(
            limit: Int?,
            cursor: String?
        ): Outcome<PageRequest> {
            val effectiveLimit = limit ?: DEFAULT_LIMIT
            if (effectiveLimit !in 1..MAX_LIMIT) {
                return Outcome.Err(
                    DomainError(
                        code = ErrorCode.INVALID_REQUEST,
                        message = "limit must be between 1 and $MAX_LIMIT.",
                        detail =
                            ErrorDetail.InvalidRequest(
                                listOf(FieldViolation("limit", "must be between 1 and $MAX_LIMIT", effectiveLimit.toString()))
                            )
                    )
                )
            }
            if (cursor == null) return Outcome.Ok(PageRequest(effectiveLimit, 0))
            return CursorCodec.decode(cursor).map { offset -> PageRequest(effectiveLimit, offset) }
        }
    }
}
