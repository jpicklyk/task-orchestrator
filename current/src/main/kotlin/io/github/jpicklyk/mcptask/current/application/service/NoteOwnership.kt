package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import java.util.UUID

/**
 * The seat rule (ownership rule 2): a seat-named key on an item in an open run is governed by the seat, never by the
 * writer's identity and never agent-overridable. R21 ships only the seam ([NONE]); the open-run rule is supplied by R23.
 * A non-null return refuses the write and is returned as is, so [NoteOwnership] never consults `takeover` after it.
 */
fun interface SeatRule {
    fun check(
        itemId: UUID,
        key: String,
        existing: Note?,
        callerSeat: String?
    ): DomainError?

    companion object {
        /** No seat rule: every write passes rule 2. */
        val NONE = SeatRule { _, _, _, _ -> null }
    }
}

/**
 * Note ownership, the write-time rule of who may replace or delete a note. Pure: [decide] reads nothing and writes
 * nothing; `NoteCommandService` runs it inside the write unit, against the note row re-read there.
 *
 * The owner is the note's stored `owner_id`: the principal whose write created it, whose first write adopted an unowned
 * note, or whose admin `takeover` moved it. It is kept by every other write, shared-key writes included. Attribution
 * (`actor`, `verification`) is a separate record of the last writer and is never compared here. Rules, in order:
 * 1. `shared: true` on the key's schema entry: allowed.
 * 2. [SeatRule]: a refusal wins and is never overridden by `takeover`.
 * 3. A missing note is a create (allowed). A note with no owner (every pre-4.0 note) is adopted by a non-null caller
 *    ([Decision.Adopted], takeover or not); a null caller leaves it unowned. An owner `(owner id, seat)` equal to the
 *    caller's `(principalId, callerSeat)` (exact, case-sensitive) is allowed; any other caller is
 *    `note_owned_by_other`, unless `takeover` is authorized, which transfers the note to the caller.
 *
 * `takeover` is authorized eagerly, before any rule: a caller without the capability ([takeoverAllowed]) is
 * `forbidden{capability, admin}`, and one under a seat assignment is `invalid_request` at `/takeover`, even when no
 * transfer is needed. A `takeover` where the rules already allow the write is a no-op ([Decision.Allowed]).
 *
 * Refusals never name the owner's id; `ownerSeat` appears only when the owner is a seat. A null caller against an owned
 * note is refused (fail closed).
 */
object NoteOwnership {
    sealed interface Decision {
        /** The write may proceed; the note's ownership is unchanged. */
        data object Allowed : Decision

        /**
         * The write may proceed and makes the caller the owner of an unowned existing note; [legacyActor] is that note's
         * stored actor id (its pre-4.0 author), or null.
         */
        data class Adopted(
            val legacyActor: String?
        ) : Decision

        /** The write may proceed and moves the note from owner [from] to owner [to] (recorded as an audit event). */
        data class Transferred(
            val from: String,
            val to: String
        ) : Decision
    }

    @Suppress("LongParameterList")
    fun decide(
        itemId: UUID,
        key: String,
        existing: Note?,
        entry: NoteSchemaEntry?,
        caller: ClaimHolder?,
        callerSeat: String?,
        takeover: Boolean,
        takeoverAllowed: Boolean,
        seatRule: SeatRule = SeatRule.NONE,
        ownerSeat: String? = null
    ): Outcome<Decision> {
        if (takeover && !takeoverAllowed) return Outcome.Err(adminRequired())
        if (takeover && caller?.assignmentId != null) return Outcome.Err(takeoverUnderSeat())
        if (entry?.shared == true) return Outcome.Ok(Decision.Allowed)
        seatRule.check(itemId, key, existing, callerSeat)?.let { return Outcome.Err(it) }
        if (existing == null) return Outcome.Ok(Decision.Allowed)
        val owner = existing.ownerId
        if (owner == null) {
            return Outcome.Ok(if (caller != null) Decision.Adopted(existing.actorClaim?.id) else Decision.Allowed)
        }
        if (caller != null && owner == caller.principalId && ownerSeat == callerSeat) return Outcome.Ok(Decision.Allowed)
        if (takeover && caller != null) return Outcome.Ok(Decision.Transferred(owner, caller.principalId))
        return Outcome.Err(ownedByOther(itemId, key, ownerSeat))
    }

    /** The refusal of a write to a note another actor owns: `note_owned_by_other{itemId, key, ownerSeat?}`, owner-id-free. */
    fun ownedByOther(
        itemId: UUID,
        key: String,
        ownerSeat: String? = null
    ): DomainError =
        DomainError(
            code = ErrorCode.NOTE_OWNED_BY_OTHER,
            message = "Note '$key' on item $itemId is owned by another actor.",
            detail = ErrorDetail.NoteOwnedByOther(itemId, key, ownerSeat),
            fixArgs = mapOf("key" to key, "itemId" to itemId.toString())
        )

    private fun adminRequired(): DomainError =
        DomainError(
            code = ErrorCode.FORBIDDEN,
            message = "The credential does not grant $ADMIN for the capability scope.",
            detail = ErrorDetail.Forbidden(ForbiddenScope.CAPABILITY, ADMIN),
            fixArgs = mapOf("scope" to "capability", "required" to ADMIN)
        )

    private fun takeoverUnderSeat(): DomainError {
        val reason = "takeover is not available under a seat assignment"
        return DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = reason,
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation("/takeover", reason, "true")))
        )
    }

    private const val ADMIN = "admin"
}
