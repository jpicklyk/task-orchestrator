package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Outcome of applying the [DegradedModePolicy] to a (claim, verification) pair.
 *
 * - [Trusted] — a trusted actor id was resolved; downstream logic may proceed.
 * - [Rejected] — the policy rejects the operation; the tool should return an error.
 */
sealed class PolicyResolution {
    /** The trusted actor id to use in place of (or equal to) the self-reported id. */
    data class Trusted(
        val trustedId: String
    ) : PolicyResolution()

    /** The policy requires rejection; [reason] is a human-readable explanation. */
    data class Rejected(
        val reason: String
    ) : PolicyResolution()
}

/**
 * Result of parsing an optional actor claim from a JSON object.
 */
sealed class ActorParseResult {
    /** Actor was present and parsed successfully. */
    data class Success(
        val claim: ActorClaim,
        val verification: VerificationResult
    ) : ActorParseResult()

    /** No actor object was provided (legitimate absence). */
    data object Absent : ActorParseResult()

    /** Actor object was present but invalid. */
    data class Invalid(
        val error: String
    ) : ActorParseResult()
}

/**
 * Mixin interface for tools that accept optional actor claims on write operations.
 * Provides a shared [parseActorClaim] method with default implementation.
 *
 * Tools opt into actor support by implementing this interface rather than inheriting
 * the logic from [BaseToolDefinition] (which is common to all tools).
 */
interface ActorAware {
    /**
     * Parses an optional `actor` JSON object, validates required fields,
     * constructs an [ActorClaim], and verifies it via the context's [ActorVerifier].
     *
     * @param actorObj The `actor` JSON object extracted from the input, or null if absent.
     * @param context The tool execution context (provides the actor verifier).
     * @return [ActorParseResult.Success] with claim and verification,
     *         [ActorParseResult.Absent] if no actor provided, or
     *         [ActorParseResult.Invalid] with an error message.
     */
    suspend fun parseActorClaim(
        actorObj: JsonObject?,
        context: ToolExecutionContext
    ): ActorParseResult {
        if (actorObj == null) return ActorParseResult.Absent

        val actorId =
            actorObj["id"]?.jsonPrimitive?.contentOrNull
                ?: return ActorParseResult.Invalid("actor.id is required")

        val actorKindStr =
            actorObj["kind"]?.jsonPrimitive?.contentOrNull
                ?: return ActorParseResult.Invalid("actor.kind is required")

        val actorKind =
            try {
                ActorKind.fromString(actorKindStr)
            } catch (e: IllegalArgumentException) {
                return ActorParseResult.Invalid("Invalid actor.kind: $actorKindStr")
            }

        val claim =
            ActorClaim(
                id = actorId,
                kind = actorKind,
                parent = actorObj["parent"]?.jsonPrimitive?.contentOrNull,
                proof = actorObj["proof"]?.jsonPrimitive?.contentOrNull
            )
        val verification = context.actorVerifier().verify(claim)
        return ActorParseResult.Success(claim, verification)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ActorAware::class.java)

        /**
         * Applies the [DegradedModePolicy] to determine the trusted actor identity.
         *
         * This is the central resolution point that `claim_item` (item 4) and
         * `AdvanceItemTool` ownership checks (item 5) must call when verifying that
         * the actor performing an operation matches the claim holder.
         *
         * **Policy behaviour:**
         * - [DegradedModePolicy.ACCEPT_CACHED]: Three sub-cases:
         *   1. [VerificationStatus.VERIFIED] (fresh or stale-cache where
         *      `metadata["verifiedFromCache"] == "true"`): the JWT-validated `claim.id`
         *      is returned as trusted. [JwksActorVerifier] enforces `sub == actor.id`
         *      when `requireSubMatch=true`, so `claim.id` is already the verified identity.
         *   2. [VerificationStatus.UNAVAILABLE] with `metadata["verifiedFromCache"] == "true"`:
         *      the JWT was cryptographically verified against a stale key — trust `claim.id`.
         *      (Reserved for future verifier paths that return UNAVAILABLE instead of VERIFIED
         *      on stale-cache success; [JwksActorVerifier] currently returns VERIFIED here.)
         *   3. [VerificationStatus.UNAVAILABLE] without the cache metadata flag: the JWKS
         *      endpoint is down and no cached key is available. Falls back to the self-reported
         *      `claim.id` with a WARN log, preserving pre-v3.3 implicit behavior.
         *   For all other non-VERIFIED statuses (ABSENT, UNCHECKED, REJECTED), the
         *   self-reported `claim.id` is returned as-is, preserving the pre-v3.3 implicit fallback.
         * - [DegradedModePolicy.ACCEPT_SELF_REPORTED]: Always returns the self-reported
         *   `claim.id`. Logs a deprecation-style warning when a JWKS verifier is active so
         *   operators notice they've opted out of the identity guarantee.
         * - [DegradedModePolicy.REJECT]: Returns [PolicyResolution.Rejected] for any status
         *   other than [VerificationStatus.VERIFIED]. The caller must surface this as an
         *   operation error.
         *
         * @param claim The parsed actor claim from the request.
         * @param verification The result from [ActorVerifier.verify].
         * @param policy The configured [DegradedModePolicy].
         * @return [PolicyResolution.Trusted] with the identity to use, or
         *         [PolicyResolution.Rejected] if the operation must be blocked.
         */
        fun resolveTrustedActorId(
            claim: ActorClaim,
            verification: VerificationResult,
            policy: DegradedModePolicy
        ): PolicyResolution {
            val isVerified = verification.status == VerificationStatus.VERIFIED

            return when (policy) {
                DegradedModePolicy.ACCEPT_CACHED -> {
                    when {
                        // VERIFIED (fresh or stale-cache with verifiedFromCache=true in metadata)
                        // → trust the JWT-validated actor.id. JwksActorVerifier enforces sub==actor.id
                        // so claim.id is already the cryptographically verified identity.
                        isVerified -> PolicyResolution.Trusted(claim.id)

                        // UNAVAILABLE + verifiedFromCache=true: JWT was verified against a stale key.
                        // Reserved for future verifier paths; JwksActorVerifier returns VERIFIED here.
                        verification.status == VerificationStatus.UNAVAILABLE &&
                            verification.metadata["verifiedFromCache"] == "true" ->
                            PolicyResolution.Trusted(claim.id)

                        // UNAVAILABLE without cache metadata: JWKS is down and no cached key exists.
                        // Fall back to self-reported id with WARN so operators see the degradation.
                        verification.status == VerificationStatus.UNAVAILABLE -> {
                            logger.warn(
                                "degradedModePolicy=accept-cached: JWKS unavailable with no cached key; " +
                                    "falling back to self-reported actor.id='{}'. " +
                                    "Configure a JWKS endpoint with staleOnError=true to avoid this fallback.",
                                claim.id
                            )
                            PolicyResolution.Trusted(claim.id)
                        }

                        // Other statuses (ABSENT, UNCHECKED, REJECTED): fall back to self-reported id
                        // preserving pre-v3.3 implicit behavior.
                        else -> PolicyResolution.Trusted(claim.id)
                    }
                }

                DegradedModePolicy.ACCEPT_SELF_REPORTED -> {
                    // Always trust self-reported id — operator has explicitly opted out of JWKS guarantees.
                    // Log a warning when verification was actually attempted but not VERIFIED.
                    if (!isVerified && verification.verifier != null && verification.verifier != "noop") {
                        logger.warn(
                            "degradedModePolicy=accept-self-reported: using self-reported actor.id='{}' " +
                                "despite verification status '{}' from verifier '{}'. " +
                                "This negates JWKS identity guarantees.",
                            claim.id,
                            verification.status,
                            verification.verifier
                        )
                    }
                    PolicyResolution.Trusted(claim.id)
                }

                DegradedModePolicy.REJECT -> {
                    if (isVerified) {
                        PolicyResolution.Trusted(claim.id)
                    } else {
                        val reason =
                            "degradedModePolicy=reject: actor verification status is " +
                                "'${verification.status.toJsonString()}'" +
                                (verification.reason?.let { " ($it)" } ?: "") +
                                "; operation rejected. Configure JWKS verification or change " +
                                "degraded_mode_policy to accept-cached or accept-self-reported."
                        logger.info(
                            "degradedModePolicy=reject blocking actor.id='{}': status={}, reason={}",
                            claim.id,
                            verification.status,
                            verification.reason
                        )
                        PolicyResolution.Rejected(reason)
                    }
                }
            }
        }
    }
}
