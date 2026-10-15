package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.lifecycle.SeatCaller
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import java.util.UUID

/**
 * Rule 2 of [NoteOwnership] (R23): inside an open run whose pinned schema is seat-aware, a seat-owned note key is written
 * (upsert or delete) only by its seat, never by the writer's identity and never by `takeover`. Pure: the caller reads the
 * facts inside its write unit and supplies them through a [SeatRule] closure. The outcome is three-way ([SeatVerdict]):
 * an admitted write is decisive ([SeatVerdict.Allow], rule 3 is skipped and the note's owner never moves), a refused
 * one is [SeatVerdict.Refuse], and a write outside the rule's scope is [SeatVerdict.Defer] (rule 3 decides).
 *
 * A key's owner seat follows [computeMissingBySeat]: the schema entry's `seat` when a seat of that name is declared in
 * the SAME phase as the entry's role; any other key (no seat, an undeclared or other-phase seat, an off-schema key) is
 * unowned and defers here (rule 3 decides).
 */
object NoteSeatRule {
    /**
     * The rule-2 verdict on a write of [key] on [itemId] by [caller]. [SeatVerdict.Defer] when the item is in no open run
     * ([openRunId] null), the schema is not seat-aware ([seats] empty), or the key is unowned. For a key owned by seat O:
     * a human caller is [SeatVerdict.Allow]; a member-seat token of THIS run bound to THIS item is allowed iff its seat is
     * O; the coordinator token of THIS run is allowed iff O is [SeatDefinition.COORDINATOR_WRITABLE_SEAT]; every other
     * caller (no token, another run, another item, another seat) is refused with
     * `note_owned_by_other{itemId, key, ownerSeat = O}`.
     */
    @Suppress("LongParameterList")
    fun check(
        itemId: UUID,
        key: String,
        entry: NoteSchemaEntry?,
        seats: List<SeatDefinition>,
        openRunId: UUID?,
        caller: SeatCaller
    ): SeatVerdict {
        if (openRunId == null || seats.isEmpty()) return SeatVerdict.Defer
        val owner = ownerSeatOf(entry, seats) ?: return SeatVerdict.Defer
        if (caller.human || mayWrite(itemId, owner, openRunId, caller)) return SeatVerdict.Allow
        return SeatVerdict.Refuse(NoteOwnership.ownedByOther(itemId, key, owner))
    }

    /** The seat owning [entry]'s key under [seats] (declared in the same phase as the entry's role), or null (unowned). */
    fun ownerSeatOf(
        entry: NoteSchemaEntry?,
        seats: List<SeatDefinition>
    ): String? {
        val name = entry?.seat ?: return null
        return name.takeIf { seats.any { it.name == name && it.phase == entry.role } }
    }

    private fun mayWrite(
        itemId: UUID,
        owner: String,
        openRunId: UUID,
        caller: SeatCaller
    ): Boolean {
        val seat = caller.seat ?: return false
        if (caller.runId == null || caller.runId != openRunId) return false
        if (seat == RunSeat.COORDINATOR) return owner == SeatDefinition.COORDINATOR_WRITABLE_SEAT
        return caller.itemId == itemId && seat == owner
    }
}
