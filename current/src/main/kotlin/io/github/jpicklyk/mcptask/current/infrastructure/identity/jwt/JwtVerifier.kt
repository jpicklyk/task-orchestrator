package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * The single JWT verification pipeline (AR-30). [JwksApiVerifier] is a thin
 * mapper over this class: it builds a [JwtPolicy], calls [verify], and translates the [JwtVerdict]
 * into its own result type.
 *
 * Pipeline order (crypto before claims, so a token that is both malformed and badly signed reports
 * [FailureKind.CRYPTO]):
 * 1. parse ([FailureKind.CRYPTO])
 * 2. algorithm allowlist ([FailureKind.POLICY]; an empty list accepts any algorithm)
 * 3. key set from the provider; any key-set failure is [JwtVerdict.Unavailable]
 * 4. key selection by `kid`; no match triggers one [JwksKeySetProvider.refreshForUnknownKid] and a
 *    reselect; still none = CRYPTO
 * 5. key type and signature (CRYPTO)
 * 6. claims parse, `exp` required, `nbf`, lifetime cap, `iss`, `aud` (CLAIMS)
 * 7. [JwtPolicy.requireSub] (CLAIMS)
 *
 * [verify] never throws: an unexpected exception becomes [FailureKind.INTERNAL] (cancellation
 * still propagates).
 */
class JwtVerifier(
    private val policy: JwtPolicy,
    private val keys: JwksKeySetProvider,
    private val clock: Clock = Clock.systemUTC(),
    /** Logger for the pipeline's own lines; the mappers pass theirs so log names stay stable for operators. */
    private val logger: Logger = LoggerFactory.getLogger(JwtVerifier::class.java),
) {
    /** Verifies [token] against the configured policy and key provider. */
    suspend fun verify(token: String): JwtVerdict =
        try {
            verifyInternal(token)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Unexpected exception during JWT verification: {}", e.message)
            JwtVerdict.Rejected(e.message ?: "unexpected error", FailureKind.INTERNAL)
        }

    private suspend fun verifyInternal(token: String): JwtVerdict {
        val signedJWT =
            try {
                SignedJWT.parse(token)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return rejected("failed to parse JWT: ${e.message}", FailureKind.CRYPTO)
            }

        val alg = signedJWT.header.algorithm
        if (policy.algorithms.isNotEmpty() && alg.name !in policy.algorithms) {
            return rejected("algorithm not allowed: ${alg.name}", FailureKind.POLICY)
        }

        val fetched =
            try {
                keys.getKeySet()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return JwtVerdict.Unavailable("failed to fetch JWKS: ${e.message}")
            }

        val kid = signedJWT.header.keyID
        val (jwk, cacheState) =
            selectKey(kid, fetched)
                ?: return rejected("no matching key for kid: $kid", FailureKind.CRYPTO)

        val jwsVerifier: JWSVerifier =
            try {
                when (jwk) {
                    is RSAKey -> RSASSAVerifier(jwk.toRSAPublicKey())
                    is ECKey -> ECDSAVerifier(jwk.toECPublicKey())
                    is OctetKeyPair -> Ed25519Verifier(jwk)
                    else -> return rejected("unsupported key type: ${jwk.keyType}", FailureKind.CRYPTO)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return rejected("failed to build JWS verifier: ${e.message}", FailureKind.CRYPTO)
            }

        if (!signedJWT.verify(jwsVerifier)) {
            return rejected("signature verification failed", FailureKind.CRYPTO)
        }

        // Parsed only after the signature check so a malformed AND badly signed token reports
        // CRYPTO first.
        val claims =
            try {
                signedJWT.jwtClaimsSet
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return rejected("malformed JWT claims: ${e.message}", FailureKind.CLAIMS)
            }

        return checkClaims(claims)
            ?: JwtVerdict.Verified(claims = claims, kid = kid, alg = alg.name, cacheState = cacheState)
    }

    /**
     * Selects the key for [kid] from [fetched]. With no exact match, one forced refresh is requested
     * (the provider rate-limits it and returns null when it declines or fails) and the selection is
     * repeated. Returns the key and the cache state of the set it came from, or null when no key fits.
     */
    private suspend fun selectKey(
        kid: String?,
        fetched: JwksResult,
    ): Pair<JWK, CacheState>? {
        matchByKid(kid, fetched.keys)?.let { return it to fetched.cacheState }

        val refreshed =
            try {
                keys.refreshForUnknownKid(kid.orEmpty())
            } catch (e: Exception) {
                // The provider contract is null-on-failure; a provider that throws anyway is treated
                // the same way so a broken refresh cannot turn a kid miss into INTERNAL.
                e.rethrowIfCancellation()
                logger.debug("Forced JWKS refresh for unknown kid '{}' failed: {}", kid, e.message)
                null
            }
        if (refreshed != null) {
            matchByKid(kid, refreshed.keys)?.let { return it to refreshed.cacheState }
        }

        return null
    }

    private fun matchByKid(
        kid: String?,
        set: JWKSet,
    ): JWK? = JWKSelector(JWKMatcher.Builder().keyID(kid).build()).select(set).firstOrNull()

    /** Returns the first claims-level rejection, or null when every claim check passes. */
    private fun checkClaims(claims: JWTClaimsSet): JwtVerdict.Rejected? {
        // exp is required; ClaimTimeChecks works on Instants (no Date millisecond truncation).
        val expiry = claims.expirationTime ?: return rejected("missing exp claim", FailureKind.CLAIMS)
        val now: Instant = clock.instant()
        val expiryInstant = expiry.toInstant()
        if (ClaimTimeChecks.isExpired(now, expiryInstant)) {
            return rejected("token expired", FailureKind.CLAIMS)
        }

        val notBefore = claims.notBeforeTime
        if (notBefore != null && ClaimTimeChecks.isNotYetValid(now, notBefore.toInstant())) {
            return rejected("token not yet valid", FailureKind.CLAIMS)
        }

        TokenLifetimeCap
            .violation(now, expiryInstant, claims.issueTime?.toInstant(), policy.maxTokenLifetimeSeconds)
            ?.let { return rejected(it, FailureKind.CLAIMS) }

        // Explicit issuer overrides the OIDC-discovered one; with neither, iss is not checked.
        val effectiveIssuer = policy.issuer ?: keys.getResolvedIssuer()
        if (effectiveIssuer != null && claims.issuer != effectiveIssuer) {
            return rejected("issuer mismatch: expected=$effectiveIssuer, got=${claims.issuer}", FailureKind.CLAIMS)
        }

        if (policy.audience != null) {
            val aud = claims.audience
            if (aud == null || !aud.contains(policy.audience)) {
                return rejected("audience mismatch: expected=${policy.audience}, got=$aud", FailureKind.CLAIMS)
            }
        }

        if (policy.requireSub && claims.subject == null) {
            return rejected("missing sub claim", FailureKind.CLAIMS)
        }

        return null
    }

    private fun rejected(
        reason: String,
        kind: FailureKind,
    ) = JwtVerdict.Rejected(reason, kind)
}
