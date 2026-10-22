package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome

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
