package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.error.ErrorKind
import java.util.UUID

/**
 * Structured error envelope returned by all MCP tools on failure.
 *
 * Provides enough information for an agent to decide:
 * 1. **Whether to retry** — determined by [kind]
 * 2. **When to retry** — [retryAfterMs] for [ErrorKind.SHEDDING] (null means "use own backoff")
 * 3. **Which item to act on next** — [contendedItemId] distinguishes "retry *this* item" from
 *    "pick a *different* item" without requiring string-parsing of [message].
 *
 * @property kind       Retry semantics classification.
 * @property code       Structured error code (use constants from [ErrorCodes]).
 * @property message    Human-readable description of the failure.
 * @property retryAfterMs Milliseconds to wait before retrying (populated for [ErrorKind.SHEDDING];
 *                        null means the caller should apply its own back-off).
 * @property contendedItemId UUID of the work item involved in a contention error (populated for
 *                           [ErrorKind.TRANSIENT] claim-race or version-conflict failures).
 * @property details    Optional free-text detail (e.g. a stack trace or field-level text), emitted as `details`.
 */
data class ToolError(
    val kind: ErrorKind,
    val code: String,
    val message: String,
    val retryAfterMs: Long? = null,
    val contendedItemId: UUID? = null,
    val details: String? = null
) {
    companion object {
        /**
         * Creates a [TRANSIENT][ErrorKind.TRANSIENT] error (lock contention, JWKS outage, etc.).
         *
         * @param code             Structured error code.
         * @param message          Human-readable description.
         * @param contendedItemId  UUID of the item involved in the contention (if applicable).
         */
        fun transient(
            code: String,
            message: String,
            contendedItemId: UUID? = null
        ): ToolError =
            ToolError(
                kind = ErrorKind.TRANSIENT,
                code = code,
                message = message,
                contendedItemId = contendedItemId
            )

        /**
         * Creates a [PERMANENT][ErrorKind.PERMANENT] error (validation, authorization, not-found).
         *
         * @param code    Structured error code.
         * @param message Human-readable description.
         */
        fun permanent(
            code: String,
            message: String
        ): ToolError =
            ToolError(
                kind = ErrorKind.PERMANENT,
                code = code,
                message = message
            )

        /**
         * Creates a [SHEDDING][ErrorKind.SHEDDING] error (server over-capacity).
         *
         * @param code          Structured error code.
         * @param message       Human-readable description.
         * @param retryAfterMs  Milliseconds to wait before retrying. If null the caller uses
         *                      its own backoff strategy.
         */
        fun shedding(
            code: String,
            message: String,
            retryAfterMs: Long? = null
        ): ToolError =
            ToolError(
                kind = ErrorKind.SHEDDING,
                code = code,
                message = message,
                retryAfterMs = retryAfterMs
            )
    }
}
