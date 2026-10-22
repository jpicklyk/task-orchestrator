package io.github.jpicklyk.mcptask.current.domain.error

/**
 * Classifies the retry semantics of an error. Decided by the [ErrorCode], never per call site
 * (envelope design section 2, `kind` row).
 *
 * - [TRANSIENT] - retry the same request as-is, after `retryAfterMs` when the server provides it.
 *   Typical causes: a claim or resource lease held elsewhere, an internal fault.
 * - [PERMANENT] - do not retry without changing something; the error's `fix` says what.
 *   Typical causes: validation errors, gate blocks, authorization failures, not-found.
 * - [SHEDDING] - the server is declining load; retry with backoff.
 *   Typical causes: database busy or locked past the unit-of-work deadline.
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
