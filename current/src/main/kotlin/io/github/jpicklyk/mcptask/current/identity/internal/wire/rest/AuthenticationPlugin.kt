package io.github.jpicklyk.mcptask.current.identity.internal.wire.rest

import io.github.jpicklyk.mcptask.current.identity.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.identity.BearerTokenStore
import io.github.jpicklyk.mcptask.current.identity.HashBytes
import io.github.jpicklyk.mcptask.current.identity.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.identity.TrustDecision
import io.github.jpicklyk.mcptask.current.identity.credentialResolverFor
import io.github.jpicklyk.mcptask.current.identity.directorOnlyForbidden
import io.github.jpicklyk.mcptask.current.identity.extractBearerToken
import io.github.jpicklyk.mcptask.current.identity.isDirectorOnly
import io.github.jpicklyk.mcptask.current.identity.keySetUnavailable
import io.github.jpicklyk.mcptask.current.identity.unauthenticated
import io.github.jpicklyk.mcptask.current.platform.call.LOCAL_PRINCIPAL
import io.github.jpicklyk.mcptask.current.platform.call.Principal
import io.github.jpicklyk.mcptask.current.platform.rest.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.platform.rest.auth.withHostOf
import io.github.jpicklyk.mcptask.current.platform.rest.envelope.respondEnvelopeError
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import org.slf4j.LoggerFactory

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

    /** Anchor validation for JWT `scope.root_ids` (see `CredentialResolver`); the default admits every anchor. */
    var nonRootAnchors: suspend (Set<java.util.UUID>) -> Set<java.util.UUID> = { emptySet() }

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
 * - Resolves the credential through the one bearer pipeline, `CredentialResolver`: bearer mode against the token
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
 * without a principal — routes must not call `apiPrincipal` in this case).
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
        val resolver =
            credentialResolverFor(config.authConfig, config.tokenEntries, config.jwksVerifier, config.nonRootAnchors, config.clock)

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

            val resolved =
                when (val decision = resolver?.resolve(token) ?: TrustDecision.Unauthenticated) {
                    is TrustDecision.Trusted -> decision.resolved
                    is TrustDecision.Unavailable -> {
                        call.respondEnvelopeError(keySetUnavailable(decision, logger))
                        return@onCall
                    }
                    is TrustDecision.Unauthenticated -> {
                        if (resolver == null) logger.error("JWKS auth mode configured but no JwksApiVerifier provided")
                        logger.debug("Authentication failed for request to {}", call.request.local.uri)
                        call.response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                        call.respondEnvelopeError(unauthenticated("Invalid or expired token."))
                        return@onCall
                    }
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
 * True for exactly `/api/v1/ops/director/<tool>` with a lowercase snake_case tool name: no trailing slash, no
 * percent-encoding, no dot segment, no other casing. The raw request path is checked, so anything a router might
 * normalize differently is refused rather than admitted.
 */
internal fun isDirectorOpsPath(path: String): Boolean = DIRECTOR_OPS_PATH.matches(path)

private val DIRECTOR_OPS_PATH = Regex("^/api/v1/ops/director/[a-z0-9_]+$")
