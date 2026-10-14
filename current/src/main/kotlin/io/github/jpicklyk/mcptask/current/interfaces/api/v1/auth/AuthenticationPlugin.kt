package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.telemetry.HostName
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.infrastructure.identity.CredentialResolver
import io.github.jpicklyk.mcptask.current.infrastructure.identity.DIRECTOR_CREDENTIAL_PROFILE
import io.github.jpicklyk.mcptask.current.infrastructure.identity.LOCAL_PRINCIPAL
import io.github.jpicklyk.mcptask.current.infrastructure.identity.Resolved
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelopeError
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.CLIENT_HOST_HEADER
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

/**
 * AttributeKey used to attach the resolved [Principal] to the request pipeline.
 *
 * Routes and plugins downstream of [ApiBearerAuth] retrieve the principal via:
 * ```kotlin
 * val principal = call.attributes[ApiPrincipalKey]
 * ```
 * or via the convenience extension [ApplicationCall.apiPrincipal].
 */
val ApiPrincipalKey: AttributeKey<Principal> = AttributeKey("ApiPrincipal")

/**
 * Retrieves the authenticated [Principal] attached to this call by [ApiBearerAuth].
 *
 * @throws IllegalStateException if the plugin has not been installed or the call was
 *   not authenticated (i.e., the plugin responded with 401 before this is called).
 */
fun ApplicationCall.apiPrincipal(): Principal = attributes[ApiPrincipalKey]

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

    /**
     * Path prefixes another plugin authenticates (e.g. `/mcp`, owned by the route-scoped `McpBearerAuth`): this plugin
     * neither authenticates them nor attaches a principal, so such a path is never public and never authenticated twice.
     * A prefix matches the path itself and every path below it (`/mcp`, `/mcp/knowledge`), never a sibling (`/mcpx`).
     * Matched against [io.ktor.server.request.path] (no query string).
     */
    var delegatedPrefixes: Set<String> = emptySet()
}

/**
 * Ktor application plugin that enforces bearer-token or JWT authentication on every request.
 *
 * **Behavior:**
 * - Extracts `Authorization: Bearer <token>` header.
 * - Resolves the credential through the one bearer pipeline, [CredentialResolver]: bearer mode against the token
 *   entries (SHA-256, constant-time lookup, expiry), JWKS mode through [JwksApiVerifier].
 * - On success: attaches the [Principal] (with [Principal.host] from `X-Client-Host`, else the `User-Agent`
 *   product) to `call.attributes[ApiPrincipalKey]`.
 * - A credential whose profile is `director` is confined to `POST /api/v1/ops/director/{tool}`: on any other path
 *   it answers `403 forbidden {scope: capability, required: director}`.
 * - On failure (missing/invalid/expired credential):
 *     - Responds `401 Unauthorized` with `WWW-Authenticate: Bearer error="invalid_token"`.
 *     - Short-circuits the pipeline (no further handlers are invoked).
 *
 * When [ApiAuthConfig.Disabled] is configured, the plugin is a no-op (every call passes through
 * without a principal — routes must not call [apiPrincipal] in this case).
 *
 * When [ApiAuthConfig.Unauthenticated] is configured (opt-in: `API_AUTH_MODE=none` +
 * `API_ALLOW_UNAUTHENTICATED=true`), every call is attached [LOCAL_PRINCIPAL] with no
 * credential check at all  -  see [LOCAL_PRINCIPAL] for the ADMIN/unrestricted rationale.
 *
 * Paths under [ApiAuthPluginConfig.delegatedPrefixes] (`/mcp`) are skipped in every mode: their own plugin
 * authenticates them.
 */
val ApiBearerAuth =
    createApplicationPlugin(
        name = "ApiBearerAuth",
        createConfiguration = ::ApiAuthPluginConfig,
    ) {
        val logger = LoggerFactory.getLogger("ApiBearerAuth")
        val config = pluginConfig
        val resolver = credentialResolverFor(config.authConfig, config.tokenEntries, config.jwksVerifier, config.clock)

        onCall { call ->
            val authConfig = config.authConfig

            // When API is disabled, skip authentication entirely.
            if (authConfig is ApiAuthConfig.Disabled) return@onCall

            // Paths another plugin is the authority for (/mcp: McpBearerAuth) are left to it, in every mode.
            val path = call.request.path()
            if (config.delegatedPrefixes.any { path == it || path.startsWith("$it/") }) return@onCall

            // Opt-in unauthenticated mode (API_AUTH_MODE=none + API_ALLOW_UNAUTHENTICATED=true):
            // attach the synthetic ADMIN/unrestricted principal and skip the Authorization-header
            // check entirely -- this MUST run before that check so a token-less request isn't 401'd.
            if (authConfig is ApiAuthConfig.Unauthenticated) {
                call.attributes.put(ApiPrincipalKey, LOCAL_PRINCIPAL.withHostOf(call))
                return@onCall
            }

            // Skip authentication for public endpoints (health, well-known discovery, etc.).
            // Matched on the decoded path ONLY (no query string) via call.request.path() --
            // call.request.local.uri includes the query string, so an exact publicPaths entry
            // like "/api/v1/events" would never match "/api/v1/events?token=x".
            if (config.publicPaths.contains(path) || config.publicPrefixes.any { path.startsWith(it) }) {
                return@onCall
            }

            val authHeader = call.request.headers["Authorization"]
            val token = authHeader?.let { extractBearerToken(it) }
            if (token == null) {
                logger.debug("Missing or malformed Authorization header")
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_request\"")
                call.respondEnvelopeError(unauthenticated("Missing Authorization header."))
                return@onCall
            }

            if (token.isEmpty()) {
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_request\"")
                call.respondEnvelopeError(unauthenticated("Empty token."))
                return@onCall
            }

            val resolved = resolver?.resolve(token)
            if (resolved == null) {
                if (resolver == null) logger.error("JWKS auth mode configured but no JwksApiVerifier provided")
                logger.debug("Authentication failed for request to {}", call.request.local.uri)
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                call.respondEnvelopeError(unauthenticated("Invalid or expired token."))
                return@onCall
            }

            if (resolved.isDirectorOnly() && !isDirectorOpsPath(path)) {
                logger.debug("Director-profile credential '{}' refused outside the director path", resolved.principal.id)
                call.respondEnvelopeError(directorOnlyForbidden())
                return@onCall
            }

            call.attributes.put(ApiPrincipalKey, resolved.principal.withHostOf(call))
        }
    }

/**
 * The [CredentialResolver] of [authConfig]: bearer mode resolves against [tokenEntries] only, JWKS mode through
 * [jwksVerifier] only (null when that verifier is missing, so every JWT fails closed). Null for the modes that
 * resolve no credential.
 */
internal fun credentialResolverFor(
    authConfig: ApiAuthConfig,
    tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
    jwksVerifier: JwksApiVerifier?,
    clock: () -> java.time.Instant
): CredentialResolver? =
    when (authConfig) {
        is ApiAuthConfig.Bearer -> CredentialResolver(tokenEntries, null, clock)
        is ApiAuthConfig.Jwks -> jwksVerifier?.let { CredentialResolver(emptyMap(), it, clock) }
        // The SSE route resolves in Disabled mode too (tests wire it without the REST plugin); it then accepts
        // whichever source is configured, exactly as before the resolver existed.
        is ApiAuthConfig.Disabled -> CredentialResolver(tokenEntries, jwksVerifier, clock)
        is ApiAuthConfig.Unauthenticated -> null
    }

/** True when this credential is confined to the director path (token-file `profile: director`). */
internal fun Resolved.isDirectorOnly(): Boolean = profile == DIRECTOR_CREDENTIAL_PROFILE

/**
 * True for exactly `/api/v1/ops/director/<tool>` with a lowercase snake_case tool name: no trailing slash, no
 * percent-encoding, no dot segment, no other casing. The raw request path is checked, so anything a router might
 * normalize differently is refused rather than admitted.
 */
internal fun isDirectorOpsPath(path: String): Boolean = DIRECTOR_OPS_PATH.matches(path)

private val DIRECTOR_OPS_PATH = Regex("^/api/v1/ops/director/[a-z0-9_]+$")

/** The `forbidden` error of a director-profile credential used outside the director path. */
internal fun directorOnlyForbidden(): DomainError = ScopeGuard.forbidden(ForbiddenScope.CAPABILITY, DIRECTOR_CREDENTIAL_PROFILE)

/** This principal with [Principal.host] set from the request: `X-Client-Host`, else the `User-Agent` product. */
internal fun Principal.withHostOf(call: ApplicationCall): Principal =
    copy(host = HostName.forRest(call.request.header(CLIENT_HOST_HEADER), call.request.header(HttpHeaders.UserAgent)))

/**
 * The `unauthenticated` error (401) of a rejected credential. The RFC 6750 `WWW-Authenticate` challenge the caller sets
 * next to it keeps its `error=` attribute; the body is the 4.0 error envelope.
 */
internal fun unauthenticated(message: String): DomainError = DomainError(ErrorCode.UNAUTHENTICATED, message)

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
