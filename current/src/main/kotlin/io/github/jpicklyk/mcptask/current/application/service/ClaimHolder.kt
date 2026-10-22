package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * Who a write is made by, as the claim columns see it: the principal id and the seat assignment the write is made
 * under (R22; null for a call without a seat token). The PAIR is the holder: a seat and a token-less call of the same
 * principal, or two seats of it, are different holders.
 *
 * The holder id is an identity: no error a fence answers names it.
 */
data class ClaimHolder(
    val principalId: String,
    val assignmentId: UUID? = null
) {
    /**
     * True when [item]'s claim columns name this holder pair: `claimedBy` equals [principalId] (exact, case-sensitive)
     * AND `claimAssignmentId` equals [assignmentId] (both null, or the same id). Claim freshness is not considered.
     */
    fun holds(item: WorkItem): Boolean = item.claimedBy == principalId && item.claimAssignmentId == assignmentId

    companion object {
        /**
         * The holder of a call made by the principal whose audited claim is [claim]: its id (`api:<id>` for an
         * authenticated caller, `local` for the unauthenticated local principal), paired with [assignmentId], the seat
         * assignment of the call's seat token (null without one). There is no self-declared actor and no policy to
         * reject the principal, so this cannot fail; it is the pair the OWNERSHIP gate compares.
         *
         * Takes the principal's audited claim and the assignment id rather than the `CallContext` itself:
         * `application/service` may not reach `application.api` (IdentityPackageTest S7).
         */
        fun resolve(
            claim: ActorClaim,
            assignmentId: UUID?
        ): ClaimHolder = ClaimHolder(claim.id, assignmentId)
    }
}
