package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.plugin
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

            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ", ignoreCase = true)) {
                logger.debug("Missing or malformed Authorization header")
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_request\"")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_request", "error_description" to "Missing Authorization header"))
                return@onCall
            }

            val token = authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
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
                }

            if (principal == null) {
                logger.debug("Authentication failed for request to {}", call.request.local.uri)
                call.response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token", "error_description" to "Invalid or expired token"))
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
