package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.infrastructure.identity.LOCAL_PRINCIPAL
import io.github.jpicklyk.mcptask.current.infrastructure.identity.TrustDecision
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.directorOnlyForbidden
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.extractBearerToken
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.isDirectorOnly
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.keySetUnavailable
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.unauthenticated
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.withHostOf
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelopeError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

/** The MCP Streamable HTTP session header (case-insensitive on the wire). */
internal const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"

/** The principal [McpBearerAuth] established for this `/mcp` request. */
internal val McpPrincipalKey: AttributeKey<Principal> = AttributeKey("McpPrincipal")

/** Configuration of [McpBearerAuth]. */
class McpBearerAuthConfig {
    /** How a request establishes its caller. Required: the plugin refuses to install without it (no fail-open default). */
    var mode: McpAuthMode? = null

    /** The session bindings shared with the MCP adapter (its tool calls read them). Required: no fail-open default. */
    var sessions: McpSessionPrincipals? = null

    /** The exact path of the director-profile endpoint: the only `/mcp` path a `profile: director` credential may use. */
    var directorPath: String = "/mcp/director"
}

/**
 * Authenticates every `/mcp` request (R14): route-scoped on the MCP mount, after the Host guard and before the MCP SDK
 * handles anything, so a refusal is the 4.0 error envelope (never JSON-RPC) and the SDK is never reached.
 *
 * Per request:
 * 1. **Principal.** [McpAuthMode.Local]: the local principal. [McpAuthMode.Credential]: the `Authorization: Bearer`
 *    credential, resolved through R10's one pipeline. Missing, malformed or empty -> 401 `unauthenticated` with
 *    `WWW-Authenticate: Bearer error="invalid_request"`; unresolved or expired -> 401 `unauthenticated` with
 *    `error="invalid_token"`. A `profile: director` credential is admitted on the director path only; elsewhere 403
 *    `forbidden {scope: capability, required: director}`.
 * 2. **Session.** A request carrying `Mcp-Session-Id` binds that session to the principal when it is unbound and the
 *    SDK serves the id (an id the SDK did not issue binds nothing and the SDK answers its own 404); when it is bound
 *    to another principal the request is 401 `unauthenticated` (the message names neither principal) and no tool
 *    runs. `DELETE` (the session's end) forgets the binding. The `initialize` request carries no session id: its
 *    session is bound from the `Mcp-Session-Id` response header, and in any case before any request of that session
 *    reaches a tool (an unbound session's tool call has no principal and the dispatcher refuses it). A POST without a
 *    session id first reaps SDK sessions left without a transport id by earlier rejected requests (see
 *    [McpSessionPrincipals.sweepOrphans]).
 */
val McpBearerAuth =
    createRouteScopedPlugin(
        name = "McpBearerAuth",
        createConfiguration = ::McpBearerAuthConfig,
    ) {
        val logger = LoggerFactory.getLogger("McpBearerAuth")
        val mode = checkNotNull(pluginConfig.mode) { "McpBearerAuth requires an explicit McpAuthMode; none was configured." }
        val sessions =
            checkNotNull(pluginConfig.sessions) { "McpBearerAuth requires the shared McpSessionPrincipals; none was configured." }
        val directorPath = pluginConfig.directorPath

        onCall { call ->
            val principal =
                when (mode) {
                    is McpAuthMode.Local -> LOCAL_PRINCIPAL.withHostOf(call)
                    is McpAuthMode.Credential -> credentialPrincipal(call, mode, directorPath, logger) ?: return@onCall
                }
            call.attributes.put(McpPrincipalKey, principal)

            val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
            if (sessionId == null) {
                // A POST without a session id is the only request that makes the SDK create a session; reap the ones
                // earlier rejected requests left behind (before the SDK handles this one, so it is never its own victim).
                if (call.request.httpMethod == HttpMethod.Post) sessions.sweepOrphans()
                return@onCall
            }
            when (sessions.bind(sessionId, principal)) {
                BindOutcome.BOUND -> Unit
                BindOutcome.UNKNOWN -> {
                    // Not an id the SDK serves: bind nothing; the SDK answers its own 404.
                    logger.debug("/mcp request named a session the SDK does not serve")
                    return@onCall
                }
                BindOutcome.OTHER_PRINCIPAL -> {
                    logger.debug("Refused a /mcp request whose session is bound to another principal")
                    if (mode is McpAuthMode.Credential) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer error=\"invalid_token\"")
                    call.respondEnvelopeError(unauthenticated("The session belongs to another principal."))
                    return@onCall
                }
            }
            if (call.request.httpMethod == HttpMethod.Delete) sessions.forget(sessionId)
        }

        // The initialize request has no session id yet: bind the session the SDK just created to its principal.
        onCallRespond { call, _ ->
            if (call.request.headers[MCP_SESSION_ID_HEADER] != null) return@onCallRespond
            val sessionId = call.response.headers[MCP_SESSION_ID_HEADER] ?: return@onCallRespond
            val principal = call.attributes.getOrNull(McpPrincipalKey) ?: return@onCallRespond
            sessions.bind(sessionId, principal)
        }
    }

/**
 * The principal of a [McpAuthMode.Credential] request, or null once a refusal was sent (the caller must stop).
 */
private suspend fun credentialPrincipal(
    call: ApplicationCall,
    mode: McpAuthMode.Credential,
    directorPath: String,
    logger: org.slf4j.Logger
): Principal? {
    val token = call.request.headers[HttpHeaders.Authorization]?.let { extractBearerToken(it) }
    if (token == null || token.isEmpty()) {
        call.response.header(HttpHeaders.WWWAuthenticate, "Bearer error=\"invalid_request\"")
        call.respondEnvelopeError(unauthenticated(if (token == null) "Missing Authorization header." else "Empty token."))
        return null
    }
    val resolved =
        when (val decision = mode.resolver?.resolve(token) ?: TrustDecision.Unauthenticated) {
            is TrustDecision.Trusted -> decision.resolved
            is TrustDecision.Unavailable -> {
                call.respondEnvelopeError(keySetUnavailable(decision, logger))
                return null
            }
            is TrustDecision.Unauthenticated -> {
                if (mode.resolver == null) logger.error("JWKS auth mode configured but no JwksApiVerifier provided")
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer error=\"invalid_token\"")
                call.respondEnvelopeError(unauthenticated("Invalid or expired token."))
                return null
            }
        }
    if (resolved.isDirectorOnly() && call.request.path() != directorPath) {
        logger.debug("Director-profile credential '{}' refused outside the director MCP path", resolved.principal.id)
        call.respondEnvelopeError(directorOnlyForbidden())
        return null
    }
    return resolved.principal.withHostOf(call)
}
