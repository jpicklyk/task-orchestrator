package io.github.jpicklyk.mcptask.current.infrastructure.identity

import java.net.MalformedURLException
import java.net.URL

/**
 * True when [host] is a literal loopback address: `localhost` (case-insensitive), an IPv4
 * literal beginning `127.` with four valid octets, or `::1` (with or without the `[...]`
 * literal-IPv6 brackets [URL.getHost] may retain). No DNS resolution is performed — a host
 * that merely resolves to loopback (or is crafted to look like one, e.g.
 * `127.0.0.1.evil.com` or `localhost.evil.com`) is rejected.
 *
 * Top-level and `internal` so it is shared, verbatim, by [ApiAuthConfigLoader] (REST
 * `API_JWKS_URL` / `API_JWKS_ALLOW_INSECURE_URL`) and `DefaultJwksKeySetProvider`'s https-source
 * validation — one predicate cannot drift out of sync with the other.
 */
internal fun isLiteralLoopbackHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    val normalized = host.trim().lowercase().removeSurrounding("[", "]")
    if (normalized == "localhost" || normalized == "::1") return true
    if (normalized.startsWith("127.")) {
        val octets = normalized.split(".")
        return octets.size == 4 && octets.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }
    }
    return false
}

/**
 * The key-source URL rule shared by the actor-auth YAML loader and OIDC discovery: an https URL
 * passes; plaintext http passes only when [allowInsecureUrl] is set AND the host is a literal
 * loopback address (logged as a WARN); anything else, including a malformed URL, throws
 * [IllegalArgumentException] naming [label]. REST's `API_JWKS_URL` check keeps its own messages
 * (env-var opt-in) but shares [isLiteralLoopbackHost].
 */
internal fun requireHttpsOrLoopbackKeySource(
    raw: String,
    label: String,
    allowInsecureUrl: Boolean,
    logger: org.slf4j.Logger,
) {
    val url =
        try {
            URL(raw)
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException("$label '$raw' is not a valid URL: ${e.message}")
        }

    val scheme = url.protocol?.lowercase()
    if (scheme == "https") return

    if (scheme == "http" && allowInsecureUrl && isLiteralLoopbackHost(url.host)) {
        logger.warn(
            "{} '{}' uses plaintext http, permitted because allow_insecure_url=true and host '{}' is a " +
                "loopback address. This must only be used for local development/testing.",
            label,
            raw,
            url.host,
        )
        return
    }

    throw IllegalArgumentException(
        "$label '$raw' must use https; plaintext http is permitted only for a loopback host " +
            "(localhost, 127.x.x.x, ::1) with allow_insecure_url=true.",
    )
}
