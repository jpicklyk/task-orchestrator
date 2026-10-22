package io.github.jpicklyk.mcptask.current.application.telemetry

import java.util.Locale

/**
 * Normalizes and derives the `host` value recorded on `call_log` and `events` rows: the name of the client
 * application that made a call. The value is caller-supplied text, so it is bounded and cleaned here. Host is
 * data only: no code branches on it.
 */
object HostName {
    const val MAX_LENGTH: Int = 64

    /** Host recorded for a REST call that names no client through `X-Client-Host` or `User-Agent`. */
    const val REST_FALLBACK: String = "rest"

    /**
     * N1 null stays null; N2 drops ISO control chars; N3 trims; N4 lowercases ([Locale.ROOT]); N5 keeps at most
     * [MAX_LENGTH] code points (never splitting a surrogate pair) and trims the end; N6 empty becomes null.
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.filterNot { Character.isISOControl(it) }.trim().lowercase(Locale.ROOT)
        val capped =
            if (cleaned.codePointCount(0, cleaned.length) > MAX_LENGTH) {
                cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_LENGTH)).trimEnd()
            } else {
                cleaned
            }
        return capped.ifEmpty { null }
    }

    /**
     * The normalized first product name of a `User-Agent` value (RFC 9110 10.1.5: `product = token ["/" version]`),
     * or null. A blank value or one starting with `(` has no product; later products are never consulted.
     */
    fun userAgentProduct(userAgent: String?): String? {
        val ua = userAgent?.trim()
        if (ua.isNullOrEmpty() || ua.startsWith("(")) return null
        val firstToken = ua.takeWhile { !it.isWhitespace() }
        return normalize(firstToken.substringBefore('/'))
    }

    /** REST host: the `X-Client-Host` value, else the `User-Agent` product, else [REST_FALLBACK]. */
    fun forRest(
        clientHostHeader: String?,
        userAgent: String?
    ): String = normalize(clientHostHeader) ?: userAgentProduct(userAgent) ?: REST_FALLBACK
}
