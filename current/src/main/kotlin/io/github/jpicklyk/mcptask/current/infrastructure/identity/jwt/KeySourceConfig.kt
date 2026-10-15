package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig

/**
 * Where [DefaultJwksKeySetProvider] loads keys from and how it caches them -- the key-source half
 * of a verifier configuration, built from the REST verifier's config ([ApiAuthConfig.Jwks.toKeySource]) so
 * the provider never depends on a verifier's own config type.
 *
 * @param oidcDiscovery OIDC discovery document URL (its `jwks_uri` and `issuer` are discovered).
 * @param jwksUri Direct JWKS endpoint URL.
 * @param jwksPath Local JWKS file, resolved against the provider's config base directory.
 * @param cacheTtlSeconds How long a fetched key set is served before a refresh.
 * @param staleOnError Serve the last good key set when a refresh fails (bounded by [maxStaleSeconds]).
 * @param maxStaleSeconds The oldest a stale key set may be (measured from its real fetch time)
 *   before the provider reports the key set unavailable instead of serving it.
 * @param allowInsecureUrl Admits plaintext http for a literal loopback host and the loopback
 *   address class in the egress guard.
 */
data class KeySourceConfig(
    val oidcDiscovery: String? = null,
    val jwksUri: String? = null,
    val jwksPath: String? = null,
    val cacheTtlSeconds: Long = 300,
    val staleOnError: Boolean = true,
    val maxStaleSeconds: Long = 3600,
    val allowInsecureUrl: Boolean = false,
)

/** The key-source half of the REST verifier's configuration: one static JWKS URL. */
fun ApiAuthConfig.Jwks.toKeySource(): KeySourceConfig =
    KeySourceConfig(
        jwksUri = url,
        cacheTtlSeconds = cacheTtlSeconds,
        staleOnError = true,
        maxStaleSeconds = maxStaleSeconds,
        allowInsecureUrl = allowInsecureUrl,
    )
