package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig

/**
 * What [JwtVerifier] enforces on a token. The production shape is [forApi] (REST bearer JWTs).
 *
 * @param algorithms Signing-algorithm allowlist; empty accepts any algorithm the key supports.
 * @param issuer Expected `iss`; null falls back to the issuer the key provider discovered via OIDC,
 *   and when that is null too the `iss` check is off.
 * @param audience Expected `aud` member; null turns the `aud` check off.
 * @param maxTokenLifetimeSeconds Lifetime cap enforced through [TokenLifetimeCap].
 * @param requireSub A token without a `sub` claim is rejected.
 */
data class JwtPolicy(
    val algorithms: List<String>,
    val issuer: String?,
    val audience: String?,
    val maxTokenLifetimeSeconds: Long,
    val requireSub: Boolean,
) {
    companion object {
        /** Policy for REST bearer JWTs: `sub` names the principal, so it is required. */
        fun forApi(c: ApiAuthConfig.Jwks): JwtPolicy =
            JwtPolicy(
                algorithms = c.algorithms,
                issuer = c.issuer,
                audience = c.audience,
                maxTokenLifetimeSeconds = c.maxTokenLifetimeSeconds,
                requireSub = true,
            )
    }
}
