package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Top-level auditing configuration parsed from `.taskorchestrator/config.yaml`
 * under the `auditing:` key.
 *
 * @param enabled Whether auditing is active (default true).
 * @param verifier The actor-claim verifier strategy to use.
 */
data class AuditingConfig(
    val enabled: Boolean = true,
    val verifier: VerifierConfig = VerifierConfig.Noop
)

/**
 * Discriminated union describing how actor claims on MCP transitions are verified.
 *
 * - [Noop] — no verification; all claims are accepted as-is.
 * - [Jwks] — validate JWT bearer tokens against a JWKS source.
 */
sealed class VerifierConfig {
    /** No-op verifier: every actor claim passes without inspection. */
    data object Noop : VerifierConfig()

    /**
     * JWKS-backed verifier.
     *
     * Exactly one of [oidcDiscovery], [jwksUri], or [jwksPath] must be provided;
     * the service that constructs the verifier enforces this constraint.
     *
     * @param oidcDiscovery HTTPS URL of an OIDC Discovery document (`/.well-known/openid-configuration`).
     *   The JWKS URI is fetched from the `jwks_uri` field of the discovery document.
     * @param jwksUri Direct HTTPS URL of a JWKS endpoint.
     * @param jwksPath File-system path to a local JWKS JSON file (resolution done by the provider).
     * @param issuer Expected `iss` claim value; null means "any issuer accepted".
     * @param audience Expected `aud` claim value; null means "any audience accepted".
     * @param algorithms Allowed signing algorithms (e.g. `["RS256", "ES256"]`).
     *   An empty list means "accept any algorithm supported by the JWKS".
     * @param cacheTtlSeconds How long (in seconds) to cache the fetched JWKS (default 300).
     * @param requireSubMatch When true, the JWT `sub` claim must match the actor id supplied by
     *   the caller (default true).
     */
    data class Jwks(
        val oidcDiscovery: String? = null,
        val jwksUri: String? = null,
        val jwksPath: String? = null,
        val issuer: String? = null,
        val audience: String? = null,
        val algorithms: List<String> = emptyList(),
        val cacheTtlSeconds: Long = 300,
        val requireSubMatch: Boolean = true
    ) : VerifierConfig()
}
