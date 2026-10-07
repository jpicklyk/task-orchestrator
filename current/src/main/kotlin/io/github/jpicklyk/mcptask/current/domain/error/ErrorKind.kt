package io.github.jpicklyk.mcptask.current.domain.error

/**
 * Classifies the retry semantics of an error.
 *
 * - [TRANSIENT] - the failure is temporary; the caller should retry with exponential backoff.
 *   Typical causes: lock contention, JWKS unavailable, transient DB busy.
 * - [PERMANENT] - the failure is definitive; retrying will produce the same result.
 *   Typical causes: validation errors, authorization failures, not-found.
 * - [SHEDDING] - the server is temporarily over capacity; the caller should retry after
 *   an explicit delay indicated by the error's retry-after value.
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
