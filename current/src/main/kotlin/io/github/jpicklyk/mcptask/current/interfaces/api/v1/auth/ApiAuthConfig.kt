package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.infrastructure.security.ConstantTimeCompare

// Sealed configuration for the REST API authentication layer.
// Loaded once at startup by ApiAuthConfigLoader from environment variables and
// (for bearer mode) a YAML secret file.
// Phase 2 reads this config to decide whether to install the authentication plugin
// and which verification path to take for each request.
sealed class ApiAuthConfig {
    // API is disabled (API_ENABLED=false). Phase 2 skips registering /api/v1/* routes.
    data object Disabled : ApiAuthConfig()

    // Static bearer-token mode. The tokens map is precomputed at startup: every entry's
    // key is the SHA-256 digest of the plaintext token wrapped in HashBytes for structural
    // equality, and the value is the resolved ApiPrincipal.
    // The map is immutable after construction -- token rotation requires a restart.
    data class Bearer(
        val tokens: Map<HashBytes, ApiPrincipal>,
    ) : ApiAuthConfig()

    // JWKS-validated JWT mode. JWTs are verified against the JWKS endpoint at url on
    // every request; key material is cached for cacheTtlSeconds seconds.
    // url: JWKS endpoint URL
    // issuer: Expected iss claim value
    // audience: Expected aud claim value
    // algorithms: Non-empty allowlist of accepted signing algorithms (e.g. RS256, EdDSA)
    // cacheTtlSeconds: How long to retain the JWKS in-memory before re-fetching
    data class Jwks(
        val url: String,
        val issuer: String,
        val audience: String,
        val algorithms: List<String>,
        val cacheTtlSeconds: Long,
    ) : ApiAuthConfig()
}

// Wrapper around a SHA-256 digest ByteArray that provides the structural equality
// contract required to use the digest as a Map key.
//
// Raw ByteArray identity equality makes it unsuitable for map lookups -- two independently
// computed digests of the same token would compare unequal even though they represent the
// same credential. This wrapper delegates equals to ConstantTimeCompare.equal so that
// all lookups are timing-safe, and derives hashCode from ByteArray.contentHashCode.
//
// Always wrap a digest before inserting into or looking up from ApiAuthConfig.Bearer.tokens.
class HashBytes(
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HashBytes) return false
        return ConstantTimeCompare.equal(bytes, other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}
