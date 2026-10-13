package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig

/**
 * Where [DefaultJwksKeySetProvider] loads keys from and how it caches them -- the key-source half
 * of a verifier configuration, shared by the actor verifier ([VerifierConfig.Jwks.toKeySource]) and
 * the REST verifier ([ApiAuthConfig.Jwks.toKeySource]) so the provider never depends on either
 * verifier's own config type.
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
 * @param didAllowlist Trusted issuer DIDs (DID-rooted trust).
 * @param didPattern Trusted issuer DID glob (DID-rooted trust).
 * @param didStrictRelationship Only `assertionMethod` keys of a resolved DID document are eligible.
 */
data class KeySourceConfig(
    val oidcDiscovery: String? = null,
    val jwksUri: String? = null,
    val jwksPath: String? = null,
    val cacheTtlSeconds: Long = 300,
    val staleOnError: Boolean = true,
    val maxStaleSeconds: Long = 3600,
    val allowInsecureUrl: Boolean = false,
    val didAllowlist: List<String> = emptyList(),
    val didPattern: String? = null,
    val didStrictRelationship: Boolean = true,
)

/** The key-source half of the actor verifier's configuration. */
fun VerifierConfig.Jwks.toKeySource(): KeySourceConfig =
    KeySourceConfig(
        oidcDiscovery = oidcDiscovery,
        jwksUri = jwksUri,
        jwksPath = jwksPath,
        cacheTtlSeconds = cacheTtlSeconds,
        staleOnError = staleOnError,
        maxStaleSeconds = maxStaleSeconds,
        allowInsecureUrl = allowInsecureUrl,
        didAllowlist = didAllowlist,
        didPattern = didPattern,
        didStrictRelationship = didStrictRelationship,
    )

/** The key-source half of the REST verifier's configuration: one static JWKS URL, no DID trust. */
fun ApiAuthConfig.Jwks.toKeySource(): KeySourceConfig =
    KeySourceConfig(
        jwksUri = url,
        cacheTtlSeconds = cacheTtlSeconds,
        staleOnError = true,
        maxStaleSeconds = maxStaleSeconds,
        allowInsecureUrl = allowInsecureUrl,
    )
