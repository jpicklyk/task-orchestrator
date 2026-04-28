package io.github.jpicklyk.mcptask.current.domain.model

import java.util.UUID

/**
 * Classifies the retry semantics of a [ToolError].
 *
 * - [TRANSIENT] — the failure is temporary; the caller should retry with exponential backoff.
 *   Typical causes: lock contention, JWKS unavailable, transient DB busy.
 * - [PERMANENT] — the failure is definitive; retrying will produce the same result.
 *   Typical causes: validation errors, authorization failures, not-found.
 * - [SHEDDING] — the server is temporarily over capacity; the caller should retry after
 *   an explicit delay indicated by [ToolError.retryAfterMs].
 *   Typical causes: writer queue saturated, circuit-breaker open.
 */
enum class ErrorKind {
    TRANSIENT,
    PERMANENT,
    SHEDDING;

    fun toJsonString(): String = name.lowercase()

    companion object {
        fun fromString(value: String): ErrorKind =
            entries.find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown ErrorKind: $value")
    }
}

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
 */
data class ToolError(
    val kind: ErrorKind,
    val code: String,
    val message: String,
    val retryAfterMs: Long? = null,
    val contendedItemId: UUID? = null
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
