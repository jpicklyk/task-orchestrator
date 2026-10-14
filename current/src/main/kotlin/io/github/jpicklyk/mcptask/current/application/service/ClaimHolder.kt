package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * Who a write is made by, as the claim columns see it: the principal id and, from R22 on, the seat assignment the write
 * is made under. [assignmentId] is always null until R22 and is not compared by [holds].
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
         * The holder of a call made by the principal whose audited claim is [claim]: its id (`api:<id>` for an
         * authenticated caller, `local` for the unauthenticated local principal). There is no self-declared actor and
         * no policy to reject the principal, so this cannot fail; it is the id the OWNERSHIP gate compares
         * (`callerId = actorClaim?.id`).
         *
         * Takes the principal's audited claim rather than the `CallPrincipal` itself: `application/service` may not
         * reach `application.api` (IdentityPackageTest S7).
         */
        fun resolve(claim: ActorClaim): ClaimHolder = ClaimHolder(claim.id)
    }
}
