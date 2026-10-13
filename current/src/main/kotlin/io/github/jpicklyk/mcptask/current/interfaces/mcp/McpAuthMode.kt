package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.identity.CredentialResolver
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.credentialResolverFor
import java.time.Instant

/**
 * How `/mcp` establishes its caller (R14).
 *
 * - [Local]: every request runs as the local principal (`local`, ADMIN, unrestricted). Stdio, HTTP with
 *   `API_AUTH_MODE=none`, and HTTP with the API disabled under the `MCP_ALLOW_UNAUTHENTICATED=true` opt-in.
 * - [Credential]: every request must present a bearer credential that [resolver] resolves (R10's one pipeline, the
 *   same as REST). A null [resolver] (JWKS mode without a verifier) fails every credential closed, exactly as REST.
 */
sealed interface McpAuthMode {
    data object Local : McpAuthMode

    class Credential(
        val resolver: CredentialResolver?
    ) : McpAuthMode

    companion object {
        /** The environment variable that opts HTTP `/mcp` into [Local] mode while the REST API is disabled. */
        const val ALLOW_UNAUTHENTICATED_ENV = "MCP_ALLOW_UNAUTHENTICATED"

        /**
         * The mode of [transport] (the `MCP_TRANSPORT` value) under [apiConfig]. Pure: no I/O, no logging.
         *
         * - any transport other than `http` (stdio) -> [Local];
         * - `http` + [ApiAuthConfig.Unauthenticated] -> [Local];
         * - `http` + [ApiAuthConfig.Disabled] -> [Local] when [allowUnauthenticated], else [McpAuthResolution.Refused]
         *   naming `MCP_ALLOW_UNAUTHENTICATED` (the caller fails startup);
         * - `http` + [ApiAuthConfig.Bearer] / [ApiAuthConfig.Jwks] -> [Credential] over the REST resolver.
         */
        fun resolve(
            transport: String,
            apiConfig: ApiAuthConfig,
            tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
            jwksVerifier: JwksApiVerifier?,
            clock: () -> Instant,
            allowUnauthenticated: Boolean
        ): McpAuthResolution {
            if (transport != HTTP_TRANSPORT) return McpAuthResolution.Resolved(Local)
            return when (apiConfig) {
                is ApiAuthConfig.Unauthenticated -> McpAuthResolution.Resolved(Local)
                is ApiAuthConfig.Disabled ->
                    if (allowUnauthenticated) {
                        McpAuthResolution.Resolved(Local)
                    } else {
                        McpAuthResolution.Refused(
                            "MCP_TRANSPORT=http with the REST API disabled would serve /mcp without authentication. " +
                                "Enable the API with a credential mode (API_ENABLED=true, API_AUTH_MODE=bearer or jwks) " +
                                "so /mcp authenticates its callers, or set $ALLOW_UNAUTHENTICATED_ENV=true to serve /mcp " +
                                "as the local principal on purpose."
                        )
                    }
                is ApiAuthConfig.Bearer, is ApiAuthConfig.Jwks ->
                    McpAuthResolution.Resolved(Credential(credentialResolverFor(apiConfig, tokenEntries, jwksVerifier, clock)))
            }
        }

        private const val HTTP_TRANSPORT = "http"
    }
}

/** The result of [McpAuthMode.resolve]: the [mode], or the startup refusal [detail] (`Reason.MCP_AUTH`). */
sealed interface McpAuthResolution {
    data class Resolved(
        val mode: McpAuthMode
    ) : McpAuthResolution

    data class Refused(
        val detail: String
    ) : McpAuthResolution
}
