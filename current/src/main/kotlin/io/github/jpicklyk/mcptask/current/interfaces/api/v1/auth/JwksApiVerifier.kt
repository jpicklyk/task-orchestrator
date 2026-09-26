package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.infrastructure.config.AUTH_CLOCK_SKEW_SECONDS
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.TokenLifetimeCap
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.Date

/**
 * A successfully verified API JWT: the resolved [ApiPrincipal] plus the token's own expiry.
 *
 * [expiresAt] is non-null by construction — [JwksApiVerifier.verifyWithExpiry] rejects a JWT
 * without an `exp` claim before this is built — so callers holding a long-lived connection
 * (the SSE endpoint) always have an instant to schedule an expiry check against.
 *
 * @param principal The principal resolved from the JWT's `sub` / `to_scope` / `to_capabilities`.
 * @param expiresAt The JWT's `exp` claim, as a whole-second [Instant].
 */
data class VerifiedApiToken(
    val principal: ApiPrincipal,
    val expiresAt: Instant,
)

/**
 * Verifies `Authorization: Bearer <jwt>` tokens for the REST API layer.
 *
 * Accepts a [JwksKeySetProvider] for key caching and fetching — the interface makes this
 * class testable without needing a concrete [io.github.jpicklyk.mcptask.current.infrastructure.security.JwksKeyCache]
 * instance. In production, pass a [io.github.jpicklyk.mcptask.current.infrastructure.config.DefaultJwksKeySetProvider]
 * (or the [io.github.jpicklyk.mcptask.current.infrastructure.security.JwksKeyCache] typealias for it).
 *
 * Verification steps (mirroring [JwksActorVerifier][io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier]):
 * 1. Parse the JWT.
 * 2. Check that the signing algorithm is in the configured [ApiAuthConfig.Jwks.algorithms] allowlist.
 * 3. Fetch public keys from [keyProvider] matching the JWT's `kid` header.
 * 4. Verify the signature.
 * 5. Validate `exp` / `nbf` with 60-second clock skew. `exp` is REQUIRED: a JWT without an
 *    expiration claim is rejected. RFC 7519 §4.1.4 makes `exp` optional, but an API credential
 *    that never expires cannot be revoked short of key rotation, and a non-null expiry is what
 *    lets long-lived connections (SSE) run an expiry watchdog for the life of the stream.
 * 6. Validate `iss` against [ApiAuthConfig.Jwks.issuer].
 * 7. Validate `aud` against [ApiAuthConfig.Jwks.audience].
 * 8. Extract [ApiPrincipal] from custom claims (`to_scope.root_ids`, `to_scope.tags_include`,
 *    `to_capabilities`), defaulting to read-only unrestricted scope if the claims are absent.
 *
 * @param config The JWKS auth configuration (URL, issuer, audience, algorithms, TTL).
 * @param keyProvider The [JwksKeySetProvider] to delegate key material fetching to.
 * @param clock Injectable clock for unit testing of expiry/nbf logic.
 */
class JwksApiVerifier(
    private val config: ApiAuthConfig.Jwks,
    private val keyProvider: JwksKeySetProvider,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(JwksApiVerifier::class.java)

    /**
     * Verifies [jwtString] and returns the resolved [ApiPrincipal] on success, or
     * null on any failure (invalid signature, missing/past `exp`, wrong iss/aud, etc.).
     *
     * Delegates to [verifyWithExpiry] and discards the expiry — use that overload directly when
     * the caller needs to watch the credential for the life of a long-running connection.
     *
     * Failures are logged at DEBUG to avoid leaking detail in production logs.
     */
    suspend fun verify(jwtString: String): ApiPrincipal? = verifyWithExpiry(jwtString)?.principal

    /**
     * Verifies [jwtString] and returns the resolved principal together with the token's `exp`,
     * or null on any failure (invalid signature, missing/past `exp`, wrong iss/aud, etc.).
     *
     * A JWT with no `exp` claim is a failure: see the class KDoc, step 5.
     *
     * Failures are logged at DEBUG to avoid leaking detail in production logs.
     */
    suspend fun verifyWithExpiry(jwtString: String): VerifiedApiToken? =
        try {
            verifyInternal(jwtString)
        } catch (e: Exception) {
            logger.debug("Unexpected exception during API JWT verification: {}", e.message)
            null
        }

    private suspend fun verifyInternal(jwtString: String): VerifiedApiToken? {
        // Step 1 — parse JWT
        val signedJWT =
            try {
                SignedJWT.parse(jwtString)
            } catch (e: Exception) {
                logger.debug("Failed to parse JWT: {}", e.message)
                return null
            }

        // Step 2 — algorithm allowlist
        val alg = signedJWT.header.algorithm
        if (config.algorithms.isNotEmpty() && alg.name !in config.algorithms) {
            logger.debug("JWT algorithm '{}' is not in the allowlist {}", alg.name, config.algorithms)
            return null
        }

        // Step 3 — fetch JWKS keys
        val jwksResult =
            try {
                keyProvider.getKeySet()
            } catch (e: Exception) {
                logger.warn("Failed to fetch JWKS for API verification: {}", e.message)
                return null
            }

        // Step 4 — select key by kid
        val kid = signedJWT.header.keyID
        val matcher = JWKMatcher.Builder().keyID(kid).build()
        val matchingKeys = JWKSelector(matcher).select(jwksResult.keys)
        val jwk =
            if (matchingKeys.isNotEmpty()) {
                matchingKeys.first()
            } else {
                logger.debug("No JWKS key matching kid='{}'", kid)
                return null
            }

        // Step 5 — build verifier and verify signature
        val jwsVerifier =
            try {
                when (jwk) {
                    is RSAKey -> RSASSAVerifier(jwk.toRSAPublicKey())
                    is ECKey -> ECDSAVerifier(jwk.toECPublicKey())
                    is OctetKeyPair -> Ed25519Verifier(jwk)
                    else -> {
                        logger.debug("Unsupported JWK key type: {}", jwk.keyType)
                        return null
                    }
                }
            } catch (e: Exception) {
                logger.debug("Failed to build JWS verifier: {}", e.message)
                return null
            }

        if (!signedJWT.verify(jwsVerifier)) {
            logger.debug("JWT signature verification failed")
            return null
        }

        // Step 6 — validate exp / nbf with 60-second clock skew
        val claims = signedJWT.jwtClaimsSet
        val now: Instant = clock.instant()

        // `exp` is REQUIRED — an API credential with no expiry cannot be revoked short of key
        // rotation, and a null expiry silently disables the SSE expiry watchdog for the session.
        val expiry =
            claims.expirationTime
                ?: run {
                    logger.debug("JWT rejected: missing required 'exp' claim")
                    return null
                }

        val expirySkewCutoff = Date.from(now.minusSeconds(CLOCK_SKEW_SECONDS))
        if (expiry.before(expirySkewCutoff)) {
            logger.debug("JWT expired at {}", expiry)
            return null
        }

        val notBefore = claims.notBeforeTime
        if (notBefore != null) {
            val skewAdjustedNow = Date.from(now.plusSeconds(CLOCK_SKEW_SECONDS))
            if (notBefore.after(skewAdjustedNow)) {
                logger.debug("JWT not yet valid (nbf={})", notBefore)
                return null
            }
        }

        // Lifetime cap (config.maxTokenLifetimeSeconds, default 86400 / API_JWKS_MAX_TOKEN_LIFETIME_SECONDS).
        // Predicate shared with JwksActorVerifier via TokenLifetimeCap so the two cannot drift apart.
        val expiryInstant = expiry.toInstant()
        val issueInstant = claims.issueTime?.toInstant()
        TokenLifetimeCap.violation(now, expiryInstant, issueInstant, config.maxTokenLifetimeSeconds)?.let { reason ->
            logger.debug("JWT rejected: {}", reason)
            return null
        }

        // Step 7 — validate iss
        if (claims.issuer != config.issuer) {
            logger.debug("JWT issuer mismatch: expected={}, got={}", config.issuer, claims.issuer)
            return null
        }

        // Step 8 — validate aud
        val aud = claims.audience
        if (aud == null || !aud.contains(config.audience)) {
            logger.debug("JWT audience mismatch: expected={}, got={}", config.audience, aud)
            return null
        }

        // Step 9 — extract ApiPrincipal from custom claims
        val sub =
            claims.subject
                ?: run {
                    logger.debug("JWT missing 'sub' claim")
                    return null
                }

        val scope =
            extractScope(claims) ?: run {
                logger.debug("JWT rejected: malformed to_scope claim")
                return null
            }
        val capabilities = extractCapabilities(claims)

        return VerifiedApiToken(
            principal =
                ApiPrincipal(
                    tokenId = sub,
                    scope = scope,
                    capabilities = capabilities,
                    authMode = ApiAuthMode.JWKS,
                ),
            expiresAt = expiry.toInstant(),
        )
    }

    /**
     * Extracts scope from the `to_scope` JWT claim via [ScopeClaimParser].
     *
     * If `to_scope` is absent, returns unrestricted scope (null rootIds, empty tags). If
     * `to_scope` is present but malformed (not a JSON object, an unknown key, or a malformed
     * `root_ids`/`tags_include` shape — see [ScopeClaimParser]), returns null so the caller
     * fails the whole JWT closed rather than silently widening access. A single WARN is logged
     * naming the claim path and the defect; it never includes the JWT itself.
     */
    private fun extractScope(claims: com.nimbusds.jwt.JWTClaimsSet): ApiScope? {
        val toScope =
            try {
                claims.getJSONObjectClaim("to_scope")
            } catch (e: Exception) {
                logger.warn("Rejecting JWT: to_scope claim is not a JSON object: {}", e.message)
                return null
            }

        return try {
            ScopeClaimParser.parse(toScope)
        } catch (e: IllegalArgumentException) {
            logger.warn("Rejecting JWT: to_scope {}", e.message)
            null
        }
    }

    /**
     * Extracts capabilities from the `to_capabilities` JWT claim.
     *
     * If absent, returns `[READ]` as per the plan's default (§4.3). Unknown values are still
     * dropped (not fail-closed — capability semantics are unchanged), but each drop and a
     * non-string-list claim now log a WARN naming `to_capabilities` so the widening is visible.
     */
    private fun extractCapabilities(claims: com.nimbusds.jwt.JWTClaimsSet): Set<ApiCapability> {
        val rawCaps =
            try {
                claims.getStringListClaim("to_capabilities")
            } catch (e: Exception) {
                logger.warn("JWT to_capabilities claim is present but is not a string list: {}", e.message)
                null
            }

        if (rawCaps.isNullOrEmpty()) {
            return setOf(ApiCapability.READ)
        }

        return rawCaps
            .mapNotNull { capStr ->
                try {
                    ApiCapability.fromConfigString(capStr)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Ignoring unknown value in JWT to_capabilities: '{}'", capStr)
                    null
                }
            }.toSet()
            .ifEmpty { setOf(ApiCapability.READ) }
    }

    companion object {
        // Same leeway TokenLifetimeCap and JtiReplayCache use — one source of truth (see
        // AUTH_CLOCK_SKEW_SECONDS KDoc).
        private const val CLOCK_SKEW_SECONDS = AUTH_CLOCK_SKEW_SECONDS
    }
}
