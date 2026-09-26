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
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus.ABSENT
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus.REJECTED
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus.UNAVAILABLE
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
 *    Under DID-rooted trust (`didAllowlist`/`didPattern` configured), `sub` must additionally
 *    equal `iss` exactly — regardless of `requireSubMatch` — and the verified subject is
 *    surfaced via [VerificationResult.verifiedSubject].
 *
 * A missing or blank [ActorClaim.proof] returns [ABSENT] — the caller may choose to treat
 * absent-proof actors differently from actors whose proof failed validation.
 *
 * Failure metadata is populated in [VerificationResult.metadata]:
 * - `failureKind`: one of `crypto`, `claims`, `policy`, `network`, `internal`
 * - On [VERIFIED] with stale JWKS cache: `verifiedFromCache="true"`, `cacheAgeSeconds="N"`
 */
class JwksActorVerifier(
    private val config: VerifierConfig.Jwks,
    private val keySetProvider: JwksKeySetProvider = DefaultJwksKeySetProvider(config),
    private val clock: Clock = Clock.systemUTC(),
    private val replayCache: JtiReplayCache = JtiReplayCache(clock = clock)
) : ActorVerifier {
    private val logger = LoggerFactory.getLogger(JwksActorVerifier::class.java)

    override suspend fun verify(actor: ActorClaim): VerificationResult =
        try {
            verifyInternal(actor)
        } catch (e: Exception) {
            logger.warn("Unexpected exception during actor verification for '{}': {}", actor.id, e.message)
            rejected(e.message ?: "unexpected error", "internal")
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
        // Step 1 — missing proof is not a failure; the caller decides how to handle absent actors.
        val proof = actor.proof
        if (proof.isNullOrBlank()) {
            return VerificationResult(status = ABSENT, verifier = VERIFIER_NAME, reason = "no proof provided")
        }

        // Step 2 — parse JWT.
        val signedJWT =
            try {
                SignedJWT.parse(proof)
            } catch (e: Exception) {
                return rejected("failed to parse JWT: ${e.message}", "crypto")
            }

        // Step 3 — algorithm allowlist check.
        val alg = signedJWT.header.algorithm
        if (config.algorithms.isNotEmpty() && alg.name !in config.algorithms) {
            return rejected("algorithm not allowed: ${alg.name}", "policy")
        }

        // Determine trust mode once — used in both the JWKS fetch branch and kid-lookup branch.
        val isDidTrust = config.didAllowlist.isNotEmpty() || config.didPattern != null

        // Step 4 — fetch JWKS (branched on DID-trust mode).
        //
        // Under DID trust, the issuer must be read out of the (still cryptographically
        // unverified) claims set before the fetch can even happen. Parsing that claims set is
        // wrapped in its own try/catch so a malformed claims set (e.g. non-numeric exp/iat) is
        // classified as REJECTED/failureKind=claims rather than falling into the generic
        // "failed to fetch JWKS" catch below, which would misreport it as UNAVAILABLE/network.
        val jwksResult =
            try {
                if (isDidTrust) {
                    val issuerClaims =
                        try {
                            signedJWT.jwtClaimsSet
                        } catch (e: Exception) {
                            return rejected("malformed JWT claims: ${e.message}", "claims")
                        }
                    val iss =
                        issuerClaims.issuer
                            ?: return rejected("missing iss claim under DID trust", "claims")
                    keySetProvider.getKeySetForIssuer(iss)
                } else {
                    keySetProvider.getKeySet()
                }
            } catch (e: IssuerNotTrustedException) {
                return rejected(e.message ?: "issuer not in DID trust policy", "policy")
            } catch (e: DidSecurityViolationException) {
                return rejected(e.message ?: "DID document security violation", "policy")
            } catch (e: Exception) {
                return unavailable("failed to fetch JWKS: ${e.message}")
            }
        val (jwkSet, fetchCacheState) = jwksResult

        // Step 5 — select the key matching the JWT's kid.
        val kid = signedJWT.header.keyID
        val matcher = JWKMatcher.Builder().keyID(kid).build()
        val matchingKeys = JWKSelector(matcher).select(jwkSet)
        val jwk =
            when {
                matchingKeys.isNotEmpty() -> matchingKeys.first()
                isDidTrust && config.didLooseKidMatch && jwkSet.keys.size == 1 -> {
                    logger.info(
                        "JWT kid '{}' not found in DID-resolved JWKS for issuer '{}'; using sole eligible key (loose-kid match)",
                        kid,
                        signedJWT.jwtClaimsSet.issuer
                    )
                    jwkSet.keys.first()
                }
                else -> return rejected("no matching key for kid: $kid", "crypto")
            }

        // Step 6 — build a JWS verifier from the first matching key and verify the signature.
        val jwsVerifier =
            try {
                when (jwk) {
                    is RSAKey -> RSASSAVerifier(jwk.toRSAPublicKey())
                    is ECKey -> ECDSAVerifier(jwk.toECPublicKey())
                    is OctetKeyPair -> Ed25519Verifier(jwk)
                    else -> return rejected("unsupported key type: ${jwk.keyType}", "crypto")
                }
            } catch (e: Exception) {
                return rejected("failed to build JWS verifier: ${e.message}", "crypto")
            }

        if (!signedJWT.verify(jwsVerifier)) {
            return rejected("signature verification failed", "crypto")
        }

        // Step 7 — validate standard claims.
        //
        // Parsing the claims set is wrapped in its own try/catch — even in static-JWKS mode a
        // malformed claims set (e.g. non-numeric exp/iat) must classify as REJECTED/failureKind=
        // claims. This runs AFTER signature verification (step 6) so a token that is both
        // malformed AND has a bad signature still reports "crypto" first (ordering contract
        // exercised by JwksActorVerifierExpRequiredTest); under DID trust the claims set was
        // already parsed successfully above (step 4), so this re-parse cannot fail there.
        val claims =
            try {
                signedJWT.jwtClaimsSet
            } catch (e: Exception) {
                return rejected("malformed JWT claims: ${e.message}", "claims")
            }

        // exp — required. A missing exp claim is rejected (parity with JwksApiVerifier); a
        // present exp allows 60 s of clock skew.
        val expiry =
            claims.expirationTime
                ?: return rejected("missing exp claim", "claims")
        val skewAdjusted = Date.from(clock.instant().minusSeconds(CLOCK_SKEW_SECONDS))
        if (expiry.before(skewAdjusted)) {
            return rejected("token expired", "claims")
        }

        // nbf — reject tokens not yet valid (allow 60 s of clock skew).
        val notBefore = claims.notBeforeTime
        if (notBefore != null) {
            val skewAdjusted = Date.from(clock.instant().plusSeconds(CLOCK_SKEW_SECONDS))
            if (notBefore.after(skewAdjusted)) {
                return rejected("token not yet valid", "claims")
            }
        }

        // Lifetime cap (actor_authentication.verifier.max_token_lifetime_seconds, default 86400).
        // Predicate shared with JwksApiVerifier via TokenLifetimeCap so the two cannot drift apart.
        val nowInstant = clock.instant()
        val expiryInstant = expiry.toInstant()
        val issueInstant = claims.issueTime?.toInstant()
        val lifetimeViolation =
            TokenLifetimeCap.violation(nowInstant, expiryInstant, issueInstant, config.maxTokenLifetimeSeconds)
        if (lifetimeViolation != null) {
            return rejected(lifetimeViolation, "claims")
        }

        // iss — explicit config overrides OIDC-discovered issuer
        val effectiveIssuer = config.issuer ?: keySetProvider.getResolvedIssuer()
        if (effectiveIssuer != null && claims.issuer != effectiveIssuer) {
            return rejected("issuer mismatch: expected=$effectiveIssuer, got=${claims.issuer}", "claims")
        }

        // aud
        if (config.audience != null) {
            val aud = claims.audience
            if (aud == null || !aud.contains(config.audience)) {
                return rejected("audience mismatch: expected=${config.audience}, got=$aud", "claims")
            }
        }

        // sub/iss binding under DID-rooted trust — unconditional, independent of requireSubMatch.
        // Each agent is identified by its own DID (fleet-deployment.md:358), so under DID trust the
        // JWT subject must equal the DID issuer exactly; a missing or blank sub is a mismatch.
        // Runs after signature/exp/nbf/iss/aud so an untrusted issuer still reports as "policy" and
        // a kid mismatch still reports as "crypto" ahead of this claims-level check.
        if (isDidTrust) {
            val sub = claims.subject
            if (sub.isNullOrEmpty() || sub != claims.issuer) {
                return rejected(
                    "sub/iss mismatch under DID trust: iss=${claims.issuer}, sub=$sub",
                    "claims"
                )
            }
        }

        // sub vs actor.id
        if (config.requireSubMatch) {
            val sub = claims.subject
            if (sub != actor.id) {
                return rejected("sub mismatch: expected=${actor.id}, got=$sub", "claims")
            }
        }

        // jti replay protection (opt-in, default off — see VerifierConfig.Jwks.jtiReplayProtection
        // KDoc). Runs after every other claim check has passed so a REJECTED/UNAVAILABLE token
        // never poisons the cache with an attacker-controlled jti. When enabled, jti becomes
        // required: a token without one cannot be tracked for replay, so it must be rejected
        // rather than silently let through unprotected.
        if (config.jtiReplayProtection) {
            val jti = claims.getJWTID()
            if (jti.isNullOrBlank()) {
                return rejected("missing jti claim", "claims")
            }
            val firstSeen = replayCache.checkAndRecord(claims.issuer, jti, expiryInstant)
            if (!firstSeen) {
                return rejected("jti replay detected", "policy")
            }
        }

        // Success — inspect cache state for stale-cache metadata.
        // Use the cache state returned with the JwksResult (not a separate getCacheState() call)
        // to avoid races where a concurrent verification's result could clobber a shared field.
        val successMetadata: Map<String, String> =
            if (fetchCacheState.fromStaleCache) {
                buildMap {
                    put("verifiedFromCache", "true")
                    fetchCacheState.ageSeconds?.let { put("cacheAgeSeconds", it.toString()) }
                }
            } else {
                emptyMap()
            }

        // Under DID trust, the sub/iss binding check above guarantees claims.subject == claims.issuer,
        // so it is safe to surface as the cryptographically verified identity.
        val verifiedSubject = if (isDidTrust) claims.subject else null

        // Forensic evidence: verified claims are safe to surface here only because we've reached
        // this point after signature + exp/nbf/iss/aud (+ DID sub/iss, + requireSubMatch) all
        // passed. REJECTED/UNAVAILABLE outcomes never reach this line, so their claims (still
        // attacker-controlled / unverified) are never captured as ProofClaims elsewhere.
        val proofClaims =
            ProofClaims(
                iss = claims.issuer,
                sub = claims.subject,
                aud = claims.audience?.takeIf { it.isNotEmpty() },
                // Explicit getter call (not the synthetic Kotlin property) — JWTClaimsSet.getJWTID()'s
                // all-caps "ID" suffix makes the auto-derived property name ambiguous/fragile.
                jti = claims.getJWTID(),
                iat = claims.issueTime?.toInstant()?.epochSecond,
                exp = claims.expirationTime?.toInstant()?.epochSecond,
                kid = signedJWT.header.keyID,
                alg = alg.name
            )

        return VerificationResult(
            status = VERIFIED,
            verifier = VERIFIER_NAME,
            metadata = successMetadata,
            verifiedSubject = verifiedSubject,
            proofClaims = proofClaims
        )
    }

    private fun rejected(
        reason: String,
        failureKind: String
    ) = VerificationResult(
        status = REJECTED,
        verifier = VERIFIER_NAME,
        reason = reason,
        metadata = mapOf("failureKind" to failureKind)
    )

    private fun unavailable(reason: String) =
        VerificationResult(
            status = UNAVAILABLE,
            verifier = VERIFIER_NAME,
            reason = reason,
            metadata = mapOf("failureKind" to "network")
        )

    companion object {
        private const val VERIFIER_NAME = "jwks"

        // Same leeway TokenLifetimeCap and JtiReplayCache use — one source of truth (see
        // AUTH_CLOCK_SKEW_SECONDS KDoc).
        private const val CLOCK_SKEW_SECONDS = AUTH_CLOCK_SKEW_SECONDS
    }
}
