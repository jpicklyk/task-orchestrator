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
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.DidSecurityViolationException
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
 * 3. key set -- per issuer under [JwtPolicy.didBinding] (the unverified `iss`; missing = CLAIMS,
 *    untrusted issuer or DID security violation = POLICY), else the static set; any other key-set
 *    failure is [JwtVerdict.Unavailable]
 * 4. key selection by `kid`; no match triggers one [JwksKeySetProvider.refreshForUnknownKid] and a
 *    reselect, then the DID loose-kid rule; still none = CRYPTO
 * 5. key type and signature (CRYPTO)
 * 6. claims parse, `exp` required, `nbf`, lifetime cap, `iss`, `aud` (CLAIMS)
 * 7. DID `sub == iss`, then `expectedSubject`, then [JwtPolicy.requireSub] (CLAIMS)
 * 8. `jti` replay, last, so a rejected token never records its `jti` (missing = CLAIMS, replay = POLICY)
 *
 * [verify] never throws: an unexpected exception becomes [FailureKind.INTERNAL] (cancellation
 * still propagates).
 */
class JwtVerifier(
    private val policy: JwtPolicy,
    private val keys: JwksKeySetProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val replayCache: JtiReplayCache = JtiReplayCache(clock = clock),
    /** Logger for the pipeline's own lines; the mappers pass theirs so log names stay stable for operators. */
    private val logger: Logger = LoggerFactory.getLogger(JwtVerifier::class.java),
) {
    /**
     * Verifies [token]. When [expectedSubject] is non-null the token's `sub` must equal it (the
     * actor verifier's `require_sub_match`); the check runs before the `jti` replay record.
     */
    suspend fun verify(
        token: String,
        expectedSubject: String? = null,
    ): JwtVerdict =
        try {
            verifyInternal(token, expectedSubject)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Unexpected exception during JWT verification: {}", e.message)
            JwtVerdict.Rejected(e.message ?: "unexpected error", FailureKind.INTERNAL)
        }

    private suspend fun verifyInternal(
        token: String,
        expectedSubject: String?,
    ): JwtVerdict {
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

        // Under DID trust the issuer must be read out of the (still unverified) claims set before
        // the key fetch can happen. A malformed claims set here is CLAIMS, not a fetch failure.
        var issuerForKeys: String? = null
        if (policy.didBinding) {
            val unverifiedClaims =
                try {
                    signedJWT.jwtClaimsSet
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    return rejected("malformed JWT claims: ${e.message}", FailureKind.CLAIMS)
                }
            issuerForKeys = unverifiedClaims.issuer
                ?: return rejected("missing iss claim under DID trust", FailureKind.CLAIMS)
        }

        val fetched =
            try {
                if (issuerForKeys != null) keys.getKeySetForIssuer(issuerForKeys) else keys.getKeySet()
            } catch (e: IssuerNotTrustedException) {
                return rejected(e.message ?: "issuer not in DID trust policy", FailureKind.POLICY)
            } catch (e: DidSecurityViolationException) {
                return rejected(e.message ?: "DID document security violation", FailureKind.POLICY)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return JwtVerdict.Unavailable("failed to fetch JWKS: ${e.message}")
            }

        val kid = signedJWT.header.keyID
        val (jwk, cacheState) =
            selectKey(kid, fetched, issuerForKeys)
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

        // Parsed again after the signature check so a malformed AND badly signed token reports
        // CRYPTO first; under DID trust this parse already succeeded above.
        val claims =
            try {
                signedJWT.jwtClaimsSet
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return rejected("malformed JWT claims: ${e.message}", FailureKind.CLAIMS)
            }

        return checkClaims(claims, expectedSubject)
            ?: JwtVerdict.Verified(claims = claims, kid = kid, alg = alg.name, cacheState = cacheState)
    }

    /**
     * Selects the key for [kid] from [fetched]. With no exact match, one forced refresh is requested
     * (the provider rate-limits it and returns null when it declines or fails) and the selection is
     * repeated; the DID loose-kid rule applies only after that. Returns the key and the cache state
     * of the set it came from, or null when no key fits.
     */
    private suspend fun selectKey(
        kid: String?,
        fetched: JwksResult,
        issuer: String?,
    ): Pair<JWK, CacheState>? {
        matchByKid(kid, fetched.keys)?.let { return it to fetched.cacheState }

        val refreshed =
            try {
                keys.refreshForUnknownKid(kid.orEmpty(), issuer)
            } catch (e: Exception) {
                // The provider contract is null-on-failure; a provider that throws anyway is treated
                // the same way so a broken refresh cannot turn a kid miss into INTERNAL.
                e.rethrowIfCancellation()
                logger.debug("Forced JWKS refresh for unknown kid '{}' failed: {}", kid, e.message)
                null
            }
        val current = refreshed ?: fetched
        if (refreshed != null) {
            matchByKid(kid, refreshed.keys)?.let { return it to refreshed.cacheState }
        }

        if (policy.didBinding && policy.didLooseKidMatch && current.keys.keys.size == 1) {
            logger.info(
                "JWT kid '{}' not found in DID-resolved JWKS for issuer '{}'; using sole eligible key (loose-kid match)",
                kid,
                issuer,
            )
            return current.keys.keys.first() to current.cacheState
        }
        return null
    }

    private fun matchByKid(
        kid: String?,
        set: JWKSet,
    ): JWK? = JWKSelector(JWKMatcher.Builder().keyID(kid).build()).select(set).firstOrNull()

    /** Returns the first claims-level rejection, or null when every claim check passes. */
    private fun checkClaims(
        claims: JWTClaimsSet,
        expectedSubject: String?,
    ): JwtVerdict.Rejected? {
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

        // Under DID-rooted trust each agent is its own DID, so sub must equal iss exactly; this runs
        // after the iss/aud checks so an untrusted issuer or kid miss keeps its own failure kind.
        if (policy.didBinding) {
            val sub = claims.subject
            if (sub.isNullOrEmpty() || sub != claims.issuer) {
                return rejected("sub/iss mismatch under DID trust: iss=${claims.issuer}, sub=$sub", FailureKind.CLAIMS)
            }
        }

        if (expectedSubject != null && claims.subject != expectedSubject) {
            return rejected("sub mismatch: expected=$expectedSubject, got=${claims.subject}", FailureKind.CLAIMS)
        }

        if (policy.requireSub && claims.subject == null) {
            return rejected("missing sub claim", FailureKind.CLAIMS)
        }

        // jti replay last: a token rejected by any earlier check never records its jti.
        if (policy.jtiReplay) {
            val jti = claims.getJWTID()
            if (jti.isNullOrBlank()) {
                return rejected("missing jti claim", FailureKind.CLAIMS)
            }
            if (!replayCache.checkAndRecord(claims.issuer, jti, expiryInstant)) {
                return rejected("jti replay detected", FailureKind.POLICY)
            }
        }
        return null
    }

    private fun rejected(
        reason: String,
        kind: FailureKind,
    ) = JwtVerdict.Rejected(reason, kind)
}
