package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** Always-on loopback hosts, already normalized (lowercase; brackets kept for the IPv6 literal). Any port matches. */
private val DEFAULT_ALLOWED_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

/** The single `MCP_ALLOWED_HOSTS` entry that disables the guard entirely. */
private const val DISABLE_TOKEN = "*"

private const val HOST_REJECTED_MESSAGE =
    "Host header not in the allowed list. Configure MCP_ALLOWED_HOSTS to permit additional hosts."

private val hostAllowlistLogger = LoggerFactory.getLogger("HostAllowlistPlugin")

/** One resolved allowlist entry: a normalized host token plus an optional exact port (`null` = any port). */
private data class AllowedEntry(
    val host: String,
    val port: String?
)

/** A `Host` header value parsed into a normalized host token plus its raw port string (`null` = no `:port` suffix). */
private data class ParsedHost(
    val host: String,
    val port: String?
)

/**
 * Parses a `Host: host[:port]` value per RFC 3986 3.2.2/3.2.3. Returns `null` for anything that
 * doesn't cleanly fit the grammar: blank input, an unterminated `[` IP-literal, an unbracketed
 * IPv6 literal (2+ colons outside brackets — RFC 3986 3.2.3's `port = *DIGIT` means a bracketed
 * `host` is required once a literal contains colons), or a non-digit port. `port = *DIGIT` also
 * means a bare trailing colon with zero digits after it is valid (empty string, not `null`).
 */
private fun parseHostHeader(raw: String): ParsedHost? {
    if (raw.isBlank()) return null
    if (raw.startsWith("[")) {
        val close = raw.indexOf(']')
        if (close < 0) return null
        val literal = raw.substring(0, close + 1) // includes the brackets
        val rest = raw.substring(close + 1)
        val port =
            when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1).takeIf { it.all(Char::isDigit) } ?: return null
                else -> return null // trailing garbage after the IP-literal
            }
        return ParsedHost(stripTrailingDot(literal.lowercase()), port)
    }
    return when (raw.count { it == ':' }) {
        0 -> ParsedHost(stripTrailingDot(raw.lowercase()), null)
        1 -> {
            val host = raw.substringBefore(':')
            val portStr = raw.substringAfter(':')
            if (host.isEmpty() || !portStr.all(Char::isDigit)) null else ParsedHost(stripTrailingDot(host.lowercase()), portStr)
        }
        else -> null // 2+ colons outside brackets: an unbracketed IPv6 literal — rejected, never matched
    }
}

/** Strips exactly one trailing `.` (RFC 1034 3.1 root-label equivalence) — never more than one. */
private fun stripTrailingDot(host: String): String = if (host.endsWith(".")) host.dropLast(1) else host

/**
 * Parses one `MCP_ALLOWED_HOSTS` CSV member into an [AllowedEntry], or `null` when it is
 * malformed — a scheme, a path, userinfo (`@`), or a non-digit port. The caller WARNs and drops
 * these rather than let them widen the allowlist (diagnosis decision (c)).
 */
private fun parseAllowlistEntry(raw: String): AllowedEntry? {
    if ("://" in raw || "/" in raw || "@" in raw) return null
    val parsed = parseHostHeader(raw) ?: return null
    // A bare trailing colon in a *configured* entry ("host:") isn't a meaningful "any port"
    // spelling distinct from a bare "host" — normalize both to AllowedEntry.port == null.
    return AllowedEntry(parsed.host, parsed.port?.takeIf { it.isNotEmpty() })
}

/** Builds the effective allowlist: the always-on loopback defaults plus the valid configured entries. */
private fun resolveAllowlist(appConfig: AppConfig): List<AllowedEntry> {
    val configured =
        appConfig.mcpAllowedHosts.mapNotNull { raw ->
            parseAllowlistEntry(raw).also {
                if (it == null) {
                    hostAllowlistLogger.warn(
                        "MCP_ALLOWED_HOSTS entry '{}' is not a valid host[:port] (a scheme, a path, '@', " +
                            "or a non-digit port is not allowed) — ignored; it does not widen the allowlist.",
                        raw,
                    )
                }
            }
        }
    return DEFAULT_ALLOWED_HOSTS.map { AllowedEntry(it, null) } + configured
}

private fun matches(
    host: ParsedHost,
    entries: List<AllowedEntry>,
): Boolean = entries.any { entry -> entry.host == host.host && (entry.port == null || entry.port == host.port) }

/**
 * Installs DNS-rebinding protection for the HTTP transport on this [Application]: a Setup-phase
 * interceptor that runs before every plugin and route, independent of install order (see the
 * comment at the `intercept` call for why it is not a `createApplicationPlugin`/`onCall` hook).
 *
 * A same-origin check (Origin vs. Host) alone doesn't close the DNS-rebinding gap, because the
 * attacker's page controls both headers once its hostname resolves to a loopback address — the
 * browser then sends a same-origin request that carries an attacker-controlled `Host`. Pinning
 * `Host` to a known-good allowlist, independent of `Origin`, closes it. See MCP item
 * `abf48945-d4e5-4e34-a78f-ebdfa6f5793c`'s diagnosis note ("Decisions (frozen by planning
 * seat)", (a)-(h)) for the full rationale; the summary below mirrors those decisions.
 *
 * - The always-on defaults are `localhost`, `127.0.0.1`, and `[::1]`, matching **any** port.
 *   [AppConfig.mcpAllowedHosts] (`MCP_ALLOWED_HOSTS`) EXTENDS — never replaces — this set.
 * - Hostnames compare case-insensitively (RFC 3986 3.2.2); a single trailing dot is ignored on
 *   both the request and configured sides (RFC 1034 3.1, `localhost.` == `localhost`).
 * - Matching is exact only — no subdomain, no `127.0.0.0/8` range, no IPv6 canonicalisation
 *   (`[0:0:0:0:0:0:0:1]` does not match `[::1]`), no unbracketed IPv6.
 * - A configured `host` entry matches any port; `host:port` matches that port only. A malformed
 *   entry (a scheme, a path, `@`, or a non-digit port) is dropped with a startup WARN — it never
 *   widens the allowlist.
 * - A missing `Host` header is allowed: browsers always send it (it's a Fetch forbidden header,
 *   so a page can't omit or forge it), and a non-browser caller can already set `Host` to
 *   anything, so rejecting an absent header would add no protection while breaking every
 *   `testApplication` harness (which sends none). A `Host` header that is present but blank,
 *   malformed, or repeated is rejected.
 * - `MCP_ALLOWED_HOSTS=*` (exactly, as one entry) disables the guard entirely, logging a WARN
 *   that names the env var and the rebinding risk. No glob patterns are supported.
 * - Every rejection is HTTP 403. `/mcp` and `/mcp/...` get a JSON-RPC error envelope
 *   (`{"jsonrpc":"2.0","error":{"code":-32000,"message":...}}`, no `id`) — the shape the MCP
 *   Streamable HTTP spec's Security Warning #1 expects for an invalid Origin/Host. Every other
 *   path gets [ErrorDto] with `error = "host_not_allowed"`. Neither response echoes the
 *   rejected `Host` value. A rejected call is finished immediately, so no later plugin or route
 *   handler runs for it.
 */
internal fun Application.installHostAllowlist(appConfig: AppConfig) {
    if (appConfig.mcpAllowedHosts.any { it == DISABLE_TOKEN }) {
        hostAllowlistLogger.warn(
            "MCP_ALLOWED_HOSTS contains '*' — Host header validation (DNS-rebinding protection) is " +
                "DISABLED for the HTTP transport. Every /mcp and /api/v1 request is accepted " +
                "regardless of its Host header. Set an explicit allowlist instead unless this is intentional.",
        )
        return
    }

    val allowlist = resolveAllowlist(appConfig)

    // Setup is the FIRST phase of ApplicationCallPipeline (Setup -> Monitoring -> Plugins -> Call
    // -> Fallback), and phase order is fixed regardless of install order -- unlike
    // createApplicationPlugin's onCall, which registers its interceptor in the Plugins phase and
    // therefore only ever ran before/after other plugins' onCall by INSTALL ORDER, not by any
    // real short-circuiting. Worse, onCall's call.respond() does not stop the pipeline on its
    // own: Ktor still runs every later Plugins-phase interceptor for the same call (this is what
    // let a Host-rejected request fall through to ApiBearerAuth's onCall and get overwritten with
    // 401). Calling finish() here, at Setup, terminates pipeline execution outright -- no later
    // phase runs at all -- making this genuinely the first, SDK-independent choke point ahead of
    // CORS and ApiBearerAuth, not merely the first plugin installed.
    intercept(ApplicationCallPipeline.Setup) {
        val hostHeaders = call.request.headers.getAll(HttpHeaders.Host)
        if (hostHeaders.isNullOrEmpty()) return@intercept // absent Host — allowed, see KDoc above
        // A repeated Host header (singleOrNull() == null) is rejected like a malformed one.
        val parsed = hostHeaders.singleOrNull()?.let(::parseHostHeader)
        if (parsed == null || !matches(parsed, allowlist)) {
            rejectHost(call)
            finish()
        }
    }
}

private suspend fun rejectHost(call: ApplicationCall) {
    val path = call.request.path()
    if (path == "/mcp" || path.startsWith("/mcp/")) {
        call.respond(HttpStatusCode.Forbidden, JsonRpcHostRejection())
    } else {
        call.respond(HttpStatusCode.Forbidden, ErrorDto(error = "host_not_allowed", message = HOST_REJECTED_MESSAGE))
    }
}

@Serializable
private data class JsonRpcHostRejection(
    val jsonrpc: String = "2.0",
    val error: JsonRpcErrorDetail = JsonRpcErrorDetail(),
)

@Serializable
private data class JsonRpcErrorDetail(
    val code: Int = -32000,
    val message: String = HOST_REJECTED_MESSAGE,
)
