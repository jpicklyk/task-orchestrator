package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ActorVerifier] that validates JWT actor proofs through the shared [JwtVerifier] pipeline under
 * [JwtPolicy.forActor], then maps the [JwtVerdict] onto a [VerificationResult].
 *
 * `require_sub_match` is passed to the pipeline as `expectedSubject = actor.id`. Under DID-rooted
 * trust (`didAllowlist`/`didPattern` configured), `sub` must additionally equal `iss` exactly and
 * the verified subject is surfaced via [VerificationResult.verifiedSubject].
 *
 * A missing or blank [ActorClaim.proof] returns [ABSENT] -- the caller may choose to treat
 * absent-proof actors differently from actors whose proof failed validation.
 *
 * Failure metadata is populated in [VerificationResult.metadata]:
 * - `failureKind`: one of `crypto`, `claims`, `policy`, `network`, `internal`
 * - On [VERIFIED] with stale JWKS cache: `verifiedFromCache="true"`, `cacheAgeSeconds="N"`
 */
class JwksActorVerifier(
    private val config: VerifierConfig.Jwks,
    private val keySetProvider: JwksKeySetProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val replayCache: JtiReplayCache = JtiReplayCache(clock = clock),
) : ActorVerifier {
    private val logger = LoggerFactory.getLogger(JwksActorVerifier::class.java)
    private val noIssuerWarned = AtomicBoolean(false)
    private val jwtVerifier = JwtVerifier(JwtPolicy.forActor(config), keySetProvider, clock, replayCache, logger)

    override suspend fun verify(actor: ActorClaim): VerificationResult =
        try {
            verifyInternal(actor)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Unexpected exception during actor verification for '{}': {}", actor.id, e.message)
            rejected(e.message ?: "unexpected error", FailureKind.INTERNAL.wire)
        }

    /**
     * Releases underlying [JwksKeySetProvider] resources (e.g., HTTP client).
     */
    fun close() {
        keySetProvider.close()
    }

    private suspend fun verifyInternal(actor: ActorClaim): VerificationResult {
        // Missing proof is not a failure; the caller decides how to handle absent actors.
        val proof = actor.proof
        if (proof.isNullOrBlank()) {
            return VerificationResult(status = ABSENT, verifier = VERIFIER_NAME, reason = "no proof provided")
        }

        val verdict = jwtVerifier.verify(proof, expectedSubject = if (config.requireSubMatch) actor.id else null)
        return when (verdict) {
            is JwtVerdict.Rejected -> rejected(verdict.reason, verdict.failureKind.wire)
            is JwtVerdict.Unavailable ->
                VerificationResult(
                    status = UNAVAILABLE,
                    verifier = VERIFIER_NAME,
                    reason = verdict.reason,
                    metadata = mapOf("failureKind" to "network"),
                )
            is JwtVerdict.Verified -> {
                warnOnceIfIssuerUnchecked()
                verified(verdict)
            }
        }
    }

    private fun verified(verdict: JwtVerdict.Verified): VerificationResult {
        val claims = verdict.claims
        val successMetadata: Map<String, String> =
            if (verdict.cacheState.fromStaleCache) {
                buildMap {
                    put("verifiedFromCache", "true")
                    verdict.cacheState.ageSeconds?.let { put("cacheAgeSeconds", it.toString()) }
                }
            } else {
                emptyMap()
            }

        // Under DID trust the pipeline's sub/iss binding guarantees sub == iss, so it is safe to
        // surface as the cryptographically verified identity.
        val isDidTrust = config.didAllowlist.isNotEmpty() || config.didPattern != null
        val verifiedSubject = if (isDidTrust) claims.subject else null

        // Forensic evidence: only a fully verified token reaches here, so its claims are safe to
        // record. REJECTED/UNAVAILABLE outcomes never capture (attacker-controlled) ProofClaims.
        val proofClaims =
            ProofClaims(
                iss = claims.issuer,
                sub = claims.subject,
                aud = claims.audience?.takeIf { it.isNotEmpty() },
                // Explicit getter call: JWTClaimsSet.getJWTID()'s all-caps "ID" suffix makes the
                // synthetic Kotlin property name ambiguous.
                jti = claims.getJWTID(),
                iat = claims.issueTime?.toInstant()?.epochSecond,
                exp = claims.expirationTime?.toInstant()?.epochSecond,
                kid = verdict.kid,
                alg = verdict.alg,
            )

        return VerificationResult(
            status = VERIFIED,
            verifier = VERIFIER_NAME,
            metadata = successMetadata,
            verifiedSubject = verifiedSubject,
            proofClaims = proofClaims,
        )
    }

    /**
     * `oidc_discovery` with no explicit issuer relies on the discovery document's `issuer`; when it
     * supplied none the pipeline's iss check is off and a token was accepted without it. Behaviour
     * is unchanged; warn once per verifier.
     */
    private fun warnOnceIfIssuerUnchecked() {
        if (config.issuer == null &&
            config.oidcDiscovery != null &&
            keySetProvider.getResolvedIssuer() == null &&
            noIssuerWarned.compareAndSet(false, true)
        ) {
            logger.warn(
                "OIDC discovery document supplied no issuer and none is configured; the iss claim is not checked. " +
                    "Set verifier.issuer.",
            )
        }
    }

    private fun rejected(
        reason: String,
        failureKind: String,
    ) = VerificationResult(
        status = REJECTED,
        verifier = VERIFIER_NAME,
        reason = reason,
        metadata = mapOf("failureKind" to failureKind),
    )

    companion object {
        private const val VERIFIER_NAME = "jwks"
    }
}
