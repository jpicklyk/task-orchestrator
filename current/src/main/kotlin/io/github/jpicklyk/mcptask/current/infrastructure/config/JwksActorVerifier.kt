package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus.FAILED
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus.UNVERIFIED
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus.VERIFIED
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.Date

/**
 * [ActorVerifier] implementation that validates JWT bearer tokens against a JWKS key set.
 *
 * Verification steps:
 * 1. Parse the JWT from [ActorClaim.proof].
 * 2. Check that the signing algorithm is in the configured allowlist (if non-empty).
 * 3. Fetch the public key matching the JWT's `kid` header from the [JwksKeySetProvider].
 * 4. Verify the JWT signature.
 * 5. Validate standard claims: `exp`, `iss`, `aud`, and optionally `sub` vs. [ActorClaim.id].
 *
 * A missing or blank [ActorClaim.proof] returns [UNVERIFIED] (not [FAILED]) — the caller may
 * choose to treat unverified actors differently from actors whose proof failed validation.
 */
class JwksActorVerifier(
    private val config: VerifierConfig.Jwks,
    private val keySetProvider: JwksKeySetProvider = DefaultJwksKeySetProvider(config),
    private val clock: Clock = Clock.systemUTC()
) : ActorVerifier {
    private val logger = LoggerFactory.getLogger(JwksActorVerifier::class.java)

    override suspend fun verify(actor: ActorClaim): VerificationResult =
        try {
            verifyInternal(actor)
        } catch (e: Exception) {
            logger.warn("Unexpected exception during actor verification for '{}': {}", actor.id, e.message)
            VerificationResult(status = FAILED, verifier = VERIFIER_NAME, reason = e.message)
        }

    /**
     * Releases underlying [JwksKeySetProvider] resources (e.g., HTTP client).
     */
    fun close() {
        keySetProvider.close()
    }

    // -------------------------------------------------------------------------
    // Internal implementation
    // -------------------------------------------------------------------------

    private suspend fun verifyInternal(actor: ActorClaim): VerificationResult {
        // Step 1 — missing proof is not a failure; the caller decides how to handle unverified actors.
        val proof = actor.proof
        if (proof.isNullOrBlank()) {
            return VerificationResult(status = UNVERIFIED, verifier = VERIFIER_NAME, reason = "no proof provided")
        }

        // Step 2 — parse JWT.
        val signedJWT =
            try {
                SignedJWT.parse(proof)
            } catch (e: Exception) {
                return VerificationResult(status = FAILED, verifier = VERIFIER_NAME, reason = "failed to parse JWT: ${e.message}")
            }

        // Step 3 — algorithm allowlist check.
        val alg = signedJWT.header.algorithm
        if (config.algorithms.isNotEmpty() && alg.name !in config.algorithms) {
            return VerificationResult(
                status = FAILED,
                verifier = VERIFIER_NAME,
                reason = "algorithm not allowed: ${alg.name}"
            )
        }

        // Step 4 — fetch JWKS.
        val jwkSet =
            try {
                keySetProvider.getKeySet()
            } catch (e: Exception) {
                return VerificationResult(
                    status = FAILED,
                    verifier = VERIFIER_NAME,
                    reason = "failed to fetch JWKS: ${e.message}"
                )
            }

        // Step 5 — select the key matching the JWT's kid.
        val kid = signedJWT.header.keyID
        val matcher = JWKMatcher.Builder().keyID(kid).build()
        val matchingKeys = JWKSelector(matcher).select(jwkSet)
        if (matchingKeys.isEmpty()) {
            return VerificationResult(
                status = FAILED,
                verifier = VERIFIER_NAME,
                reason = "no matching key for kid: $kid"
            )
        }

        // Step 6 — build a JWS verifier from the first matching key and verify the signature.
        val jwk = matchingKeys.first()
        val jwsVerifier =
            try {
                when (jwk) {
                    is RSAKey -> RSASSAVerifier(jwk.toRSAPublicKey())
                    is ECKey -> ECDSAVerifier(jwk.toECPublicKey())
                    is OctetKeyPair -> Ed25519Verifier(jwk)
                    else -> return VerificationResult(
                        status = FAILED,
                        verifier = VERIFIER_NAME,
                        reason = "unsupported key type: ${jwk.keyType}"
                    )
                }
            } catch (e: Exception) {
                return VerificationResult(
                    status = FAILED,
                    verifier = VERIFIER_NAME,
                    reason = "failed to build JWS verifier: ${e.message}"
                )
            }

        if (!signedJWT.verify(jwsVerifier)) {
            return VerificationResult(status = FAILED, verifier = VERIFIER_NAME, reason = "signature verification failed")
        }

        // Step 7 — validate standard claims.
        val claims = signedJWT.jwtClaimsSet

        // exp — allow 60 s of clock skew.
        val expiry = claims.expirationTime
        if (expiry != null) {
            val skewAdjusted = Date.from(clock.instant().minusSeconds(CLOCK_SKEW_SECONDS))
            if (expiry.before(skewAdjusted)) {
                return VerificationResult(status = FAILED, verifier = VERIFIER_NAME, reason = "token expired")
            }
        }

        // iss — explicit config overrides OIDC-discovered issuer
        val effectiveIssuer = config.issuer ?: keySetProvider.getResolvedIssuer()
        if (effectiveIssuer != null && claims.issuer != effectiveIssuer) {
            return VerificationResult(
                status = FAILED,
                verifier = VERIFIER_NAME,
                reason = "issuer mismatch: expected=$effectiveIssuer, got=${claims.issuer}"
            )
        }

        // aud
        if (config.audience != null) {
            val aud = claims.audience
            if (aud == null || !aud.contains(config.audience)) {
                return VerificationResult(
                    status = FAILED,
                    verifier = VERIFIER_NAME,
                    reason = "audience mismatch: expected=${config.audience}, got=$aud"
                )
            }
        }

        // sub vs actor.id
        if (config.requireSubMatch) {
            val sub = claims.subject
            if (sub != actor.id) {
                return VerificationResult(
                    status = FAILED,
                    verifier = VERIFIER_NAME,
                    reason = "sub mismatch: expected=${actor.id}, got=$sub"
                )
            }
        }

        return VerificationResult(status = VERIFIED, verifier = VERIFIER_NAME)
    }

    companion object {
        private const val VERIFIER_NAME = "jwks"
        private const val CLOCK_SKEW_SECONDS = 60L
    }
}
