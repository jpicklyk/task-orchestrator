package io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit

import io.github.jpicklyk.mcptask.current.application.tools.ActorAware
import io.github.jpicklyk.mcptask.current.application.tools.PolicyResolution
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal

/**
 * Bridges the REST API authentication layer to the existing MCP actor-audit pipeline.
 *
 * ## Design: server-synthesized actor (security-critical)
 *
 * The audited actor is ALWAYS synthesized server-side from the authenticated [ApiPrincipal].
 * Client-supplied `actor.*` fields in the request body are SILENTLY DROPPED — callers cannot
 * override audit attribution.
 *
 * The synthesized [ActorClaim] is:
 * - id = `"api:" + principal.tokenId`  (e.g. `"api:dashboard-editor"`)
 * - kind = [ActorKind.EXTERNAL]         (distinguishes API writes from agent writes)
 * - parent = null                        (no parent chain for API callers)
 * - proof = null                         (bearer proof is in the HTTP header; do NOT echo into audit)
 *
 * ## degradedModePolicy interaction (JWKS mode)
 *
 * For JWKS auth, authentication has already happened in [ApiBearerAuth] — if the JWT was invalid,
 * the request never reaches the route. However the [DegradedModePolicy] governs what happens to
 * the verification result when we call [ActorAware.resolveTrustedActorId]:
 *
 * - `reject` policy: when verification status is not VERIFIED → [PolicyResolution.Rejected] →
 *   routes return **401 Unauthorized**.
 * - `accept-cached` / `accept-self-reported`: tolerant of UNAVAILABLE — proceed with the synthesized id.
 *
 * Bearer mode: verification is not applicable (principal IS the trusted identity); we always produce
 * a [VerificationResult] with status [VerificationStatus.UNCHECKED] so the call to
 * [ActorAware.resolveTrustedActorId] always returns [PolicyResolution.Trusted].
 */
object ApiAuditBridge {
    /**
     * Synthesizes an [ActorClaim] from [principal] for use in audit persistence.
     *
     * Client `actor.*` fields are never read by this function — the claim is derived
     * purely from the server-side authenticated principal.
     */
    fun toActorClaim(principal: ApiPrincipal): ActorClaim =
        ActorClaim(
            id = "api:${principal.tokenId}",
            kind = ActorKind.EXTERNAL,
            parent = null,
            proof = null, // bearer is in HTTP header; do NOT echo into stored audit
        )

    /**
     * Builds a [VerificationResult] for the synthesized actor claim.
     *
     * - Bearer mode: verification is implicit (token was authenticated upstream) → UNCHECKED
     * - JWKS mode: treat as VERIFIED (JWT was validated by [ApiBearerAuth] before the route ran)
     *
     * The verification result is passed to [ActorAware.resolveTrustedActorId] together with the
     * [DegradedModePolicy]. Only REJECT policy with a non-VERIFIED status yields a 401.
     */
    fun toVerificationResult(principal: ApiPrincipal): VerificationResult =
        when (principal.authMode) {
            ApiAuthMode.BEARER ->
                VerificationResult(
                    status = VerificationStatus.UNCHECKED,
                    verifier = "api-bearer",
                    reason = null,
                )
            ApiAuthMode.JWKS ->
                VerificationResult(
                    status = VerificationStatus.VERIFIED,
                    verifier = "api-jwks",
                    reason = null,
                )
        }

    /**
     * Applies the [degradedModePolicy] to the synthesized claim and returns the trusted actor id.
     *
     * Returns null when the policy is [DegradedModePolicy.REJECT] and the auth mode is JWKS
     * but verification somehow failed (defensive — in practice JWKS tokens are verified by
     * [ApiBearerAuth] before routes are entered, so `toVerificationResult` returns VERIFIED).
     *
     * **Bearer mode is always trusted** regardless of [degradedModePolicy] — bearer auth has
     * no JWKS verification chain. The spec says: "API_AUTH_MODE=bearer is unaffected — bearer
     * auth has no verification chain." Bearer tokens are validated by the auth plugin before
     * any route handler runs; if a route handler sees a principal, the bearer token was valid.
     */
    fun resolveTrustedActorIdOrNull(
        principal: ApiPrincipal,
        degradedModePolicy: DegradedModePolicy,
    ): String? {
        // Bearer mode: always trusted — no JWKS chain, auth was validated at plugin level
        if (principal.authMode == ApiAuthMode.BEARER) {
            return "api:${principal.tokenId}"
        }
        // JWKS mode: run through the standard policy resolution
        val claim = toActorClaim(principal)
        val verification = toVerificationResult(principal)
        return when (val r = ActorAware.resolveTrustedActorId(claim, verification, degradedModePolicy)) {
            is PolicyResolution.Trusted -> r.trustedId
            is PolicyResolution.Rejected -> null
        }
    }
}
