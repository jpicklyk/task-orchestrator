package io.github.jpicklyk.mcptask.current.infrastructure.logging

/**
 * Bounds the size of self-reported, unverified values before they are placed into
 * [org.slf4j.MDC] — a value that ends up in every JSON log line for the duration of a call/request.
 * Without a cap, a caller-controlled field (an `actor.id` argument, an inbound HTTP path) could
 * blow up per-line log cost, or make log output harder to scan, with no ceiling.
 *
 * This caps ONLY the MDC copy of the value — callers still receive and act on the full,
 * untruncated original (see [io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter]'s
 * `actorIdFrom` KDoc). It is a logging-hygiene concern, not a validation or business-rule change.
 */
object MdcValues {
    /** Appended to a truncated value so log readers can tell it was cut, not naturally short. */
    private const val TRUNCATION_MARKER = "...[truncated]"

    /**
     * Returns [value] unchanged when its length is `<= max`. Otherwise returns the first [max]
     * characters (or `max - 1`, see below) followed by [TRUNCATION_MARKER].
     *
     * Truncation is done on `Char` (UTF-16 code unit) boundaries, since [String.length] and
     * [String.take] both operate on code units. A surrogate pair (an astral character, encoded as
     * a high surrogate followed by a low surrogate) must never be split: if the character at index
     * `max - 1` is a high surrogate, the cut is moved back one position to `max - 1` characters
     * instead of [max], keeping the pair intact (or dropping it whole).
     */
    fun bounded(
        value: String,
        max: Int
    ): String {
        if (value.length <= max) return value
        val cutLength = if (Character.isHighSurrogate(value[max - 1])) max - 1 else max
        return value.take(cutLength) + TRUNCATION_MARKER
    }
}
