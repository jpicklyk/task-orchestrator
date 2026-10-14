package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ClaimState
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.time.Instant
import java.util.UUID

/**
 * The write fence: while an item carries an active claim ([ClaimState.isActive], so a claim expiring exactly at [now]
 * has lapsed), only its holder may write to it. A lapsed or absent claim fences nothing. A null caller (a write with no
 * resolved principal) is refused on any active claim: the fence fails closed.
 *
 * This is the OWNERSHIP gate's predicate (`activeHolder` against `callerId`) restated for the note, item and
 * dependency writes and for cascades; the gate itself is unchanged. Callers run it inside the write unit, against the
 * row re-read there and the unit instant, after the item lookup and before any version compare or write policy.
 *
 * The refusal never names the holder.
 */
object FenceGuard {
    fun check(
        item: WorkItem,
        caller: ClaimHolder?,
        now: Instant
    ): Outcome<Unit> =
        if (ClaimState.isActive(item, now) && caller?.holds(item) != true) {
            Outcome.Err(notClaimHolder(item.id))
        } else {
            Outcome.Ok(Unit)
        }

    /** The refusal of a fenced write to [itemId]: `not_claim_holder{itemId}`, holder-free. */
    fun notClaimHolder(itemId: UUID): DomainError =
        DomainError(
            code = ErrorCode.NOT_CLAIM_HOLDER,
            message = "Item $itemId is claimed by another actor.",
            detail = ErrorDetail.NotClaimHolder(itemId),
            fixArgs = mapOf("itemId" to itemId.toString()),
        )
}
