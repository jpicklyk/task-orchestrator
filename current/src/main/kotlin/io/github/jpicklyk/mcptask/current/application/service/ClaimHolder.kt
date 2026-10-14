package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * Who a write is made by, as the claim columns see it: the trusted principal id and, from R22 on, the seat assignment
 * the write is made under. [assignmentId] is always null until R22 and is not compared by [holds].
 *
 * The holder id is an identity: no error a fence answers names it.
 */
data class ClaimHolder(
    val principalId: String,
    val assignmentId: UUID? = null
) {
    /** True when [item]'s claim column names this holder (exact, case-sensitive). Claim freshness is not considered. */
    fun holds(item: WorkItem): Boolean = item.claimedBy == principalId

    companion object {
        /**
         * The holder of a call made by the principal ([claim], [verification]): the trusted id
         * [ActorTrustPolicy.resolveTrustedActorId] yields under [policy]. A policy rejection is `unauthenticated`.
         *
         * Takes the principal's parts rather than the `CallPrincipal` itself: `application/service` may not reach
         * `application.api` (IdentityPackageTest S7).
         */
        fun resolve(
            claim: ActorClaim,
            verification: VerificationResult,
            policy: DegradedModePolicy
        ): Outcome<ClaimHolder> =
            when (val resolved = ActorTrustPolicy.resolveTrustedActorId(claim, verification, policy)) {
                is PolicyResolution.Trusted -> Outcome.Ok(ClaimHolder(resolved.trustedId))
                is PolicyResolution.Rejected -> Outcome.Err(AdvanceErrors.unauthenticated(resolved.reason))
            }
    }
}
