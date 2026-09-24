package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * AttributeKey used to attach the resolved [ApiPrincipal] to the request pipeline.
 *
 * Routes and plugins downstream of [ApiBearerAuth] retrieve the principal via:
 * ```kotlin
 * val principal = call.attributes[ApiPrincipalKey]
 * ```
 * or via the convenience extension [ApplicationCall.apiPrincipal].
 */
val ApiPrincipalKey: AttributeKey<ApiPrincipal> = AttributeKey("ApiPrincipal")

/**
 * Retrieves the authenticated [ApiPrincipal] attached to this call by [ApiBearerAuth].
 *
 * @throws IllegalStateException if the plugin has not been installed or the call was
 *   not authenticated (i.e., the plugin responded with 401 before this is called).
 */
fun ApplicationCall.apiPrincipal(): ApiPrincipal = attributes[ApiPrincipalKey]

/**
 * Configuration holder for [ApiBearerAuth].
 */
class ApiAuthPluginConfig {
    /** The authentication configuration to enforce. Must be set before the plugin is installed. */
    var authConfig: ApiAuthConfig = ApiAuthConfig.Disabled

    /** JWKS verifier — required when [authConfig] is [ApiAuthConfig.Jwks]. */
    var jwksVerifier: JwksApiVerifier? = null

    /**
     * Pre-loaded token entries (with expiry metadata) for bearer mode.
     * Loaded via [BearerTokenStore.loadWithEntries] at startup.
     */
    var tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry> = emptyMap()

    /** Injectable clock for expiry testing in bearer mode. */
    var clock: () -> java.time.Instant = { java.time.Instant.now() }

    /**
     * Exact request paths that bypass authentication entirely (e.g. `/api/v1/health`).
     * Matched against [io.ktor.server.request.path] — the decoded path ONLY, with no query
     * string — so a request like `/api/v1/events?token=x` still matches a `/api/v1/events`
     * entry. (Matching against [io.ktor.server.request.ApplicationRequest.uri] would include
     * the query string and never match an exempted path that carries query parameters.)
     */
    var publicPaths: Set<String> = setOf("/api/v1/health")

    /**
     * Path prefixes that bypass authentication entirely (e.g. `/.well-known/`).
     * Matched against [io.ktor.server.request.path] (no query string) — requests whose path
     * starts with any of these prefixes are passed through without credential validation.
     */
    var publicPrefixes: Set<String> = setOf("/.well-known/")
}

/**
 * Ktor application plugin that enforces bearer-token or JWT authentication on every request.
 *
 * **Installation (Phase 2):**
 * ```kotlin
 * install(ApiBearerAuth) {
 *     authConfig = loadedBearerConfig
 *     tokenEntries = store.loadWithEntries()
 * }
 * ```
 *
 * **Behavior:**
 * - Extracts `Authorization: Bearer <token>` header.
 * - Resolves the credential to an [ApiPrincipal]:
 *     - Bearer mode: computes SHA-256 of the presented token, constant-time lookup in token map,
 *       checks expiry.
 *     - JWKS mode: validates the JWT via [JwksApiVerifier].
 * - On success: attaches the principal to `call.attributes[ApiPrincipalKey]`.
 * - On failure (missing/invalid/expired credential):
 *     - Responds `401 Unauthorized` with `WWW-Authenticate: Bearer error="invalid_token"`.
 *     - Short-circuits the pipeline (no further handlers are invoked).
 *
 * When [ApiAuthConfig.Disabled] is configured, the plugin is a no-op (every call passes through
 * without a principal — routes must not call [apiPrincipal] in this case).
 *
 * When [ApiAuthConfig.Unauthenticated] is configured (opt-in: `API_AUTH_MODE=none` +
 * `API_ALLOW_UNAUTHENTICATED=true`), every call is attached [LOCAL_UNAUTH_PRINCIPAL] with no
 * credential check at all — see [LOCAL_UNAUTH_PRINCIPAL] for the ADMIN/unrestricted rationale.
 */
val ApiBearerAuth =
    createApplicationPlugin(
        name = "ApiBearerAuth",
        createConfiguration = ::ApiAuthPluginConfig,
    ) {
        val logger = LoggerFactory.getLogger("ApiBearerAuth")
        val config = pluginConfig

        onCall { call ->
            val authConfig = config.authConfig

            // When API is disabled, skip authentication entirely.
            if (authConfig is ApiAuthConfig.Disabled) return@onCall

            // Opt-in unauthenticated mode (API_AUTH_MODE=none + API_ALLOW_UNAUTHENTICATED=true):
            // attach the synthetic ADMIN/unrestricted principal and skip the Authorization-header
            // check entirely -- this MUST run before that check so a token-less request isn't 401'd.
            if (authConfig is ApiAuthConfig.Unauthenticated) {
                call.attributes.put(ApiPrincipalKey, LOCAL_UNAUTH_PRINCIPAL)
                return@onCall
            }

            // Skip authentication for public endpoints (health, well-known discovery, etc.).
            // Matched on the decoded path ONLY (no query string) via call.request.path() --
            // call.request.local.uri includes the query string, so an exact publicPaths entry
            // like "/api/v1/events" would never match "/api/v1/events?token=x".
            val path = call.request.path()
            if (config.publicPaths.contains(path) || config.publicPrefixes.any { path.startsWith(it) }) {
                return@onCall
            }

            val authHeader = call.request.headers["Authorization"]
            val token = authHeader?.let { extractBearerToken(it) }
            if (token == null) {
                logger.debug("Missing or malformed Authorization header")
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_request\"")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(
                        "error" to "invalid_request",
                        "error_description" to "Missing Authorization header"
                    )
                )
                return@onCall
            }

            if (token.isEmpty()) {
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_request\"")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_request", "error_description" to "Empty token"))
                return@onCall
            }

            val principal: ApiPrincipal? =
                when (authConfig) {
                    is ApiAuthConfig.Bearer -> {
                        // Compute SHA-256 of presented token and constant-time lookup
                        val digest = sha256(token)
                        val entry = config.tokenEntries[HashBytes(digest)]
                        if (entry == null) {
                            null
                        } else {
                            val expiry = entry.expiresAt
                            if (expiry != null && config.clock().isAfter(expiry)) {
                                logger.debug("Bearer token expired for tokenId='{}'", entry.principal.tokenId)
                                null
                            } else {
                                entry.principal
                            }
                        }
                    }

                    is ApiAuthConfig.Jwks -> {
                        val verifier = config.jwksVerifier
                        if (verifier == null) {
                            logger.error("JWKS auth mode configured but no JwksApiVerifier provided")
                            null
                        } else {
                            verifier.verify(token)
                        }
                    }

                    is ApiAuthConfig.Disabled -> null // unreachable due to guard above
                    is ApiAuthConfig.Unauthenticated -> null // unreachable due to guard above
                }

            if (principal == null) {
                logger.debug("Authentication failed for request to {}", call.request.local.uri)
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(
                        "error" to "invalid_token",
                        "error_description" to "Invalid or expired token"
                    )
                )
                return@onCall
            }

            call.attributes.put(ApiPrincipalKey, principal)
        }
    }

/** Computes SHA-256 of the UTF-8 encoding of [input]. */
private fun sha256(input: String): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(input.toByteArray(Charsets.UTF_8))
}

/**
 * Parses an `Authorization` header value for the `Bearer` auth-scheme (RFC 7235 §2.1,
 * RFC 6750 §2.1): `credentials = auth-scheme [ 1*SP ( token68 / #auth-param ) ]`.
 *
 * Returns `null` unless [headerValue] starts with the case-insensitive literal `Bearer` followed
 * by at least one space (`SP`, 0x20) — this codebase treats a single literal space as satisfying
 * `1*SP`. A tab, another separator, or no separator at all (bare `"Bearer"`, or a mismatched
 * scheme like `"Basic T"`) does not match and returns `null`.
 *
 * When it matches, returns everything after that one separator, trimmed of surrounding
 * whitespace — this MAY be `""` (e.g. `"Bearer   "` → `""`); callers that require a non-empty
 * credential must check for that themselves.
 *
 * Only ONE `Bearer`/`bearer` prefix is ever stripped: `"Bearer bearer T"` returns `"bearer T"`,
 * not `"T"`. RFC 6750's `b64token` grammar has no internal whitespace, so a value containing a
 * second, un-stripped `bearer ` prefix cannot be a valid token — callers should reject it as
 * `invalid_token` rather than silently authenticating the inner value.
 */
internal fun extractBearerToken(headerValue: String): String? {
    val schemeLength = "Bearer".length
    if (headerValue.length <= schemeLength) return null
    if (!headerValue.regionMatches(0, "Bearer", 0, schemeLength, ignoreCase = true)) return null
    if (headerValue[schemeLength] != ' ') return null
    return headerValue.substring(schemeLength + 1).trim()
}
