package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import java.util.UUID

/**
 * Who sends a transition or a note write, as seat enforcement (R23) sees it: the seat token's [seat], [runId] and member
 * [itemId] (all null without a token; [itemId] is null for the run-scoped coordinator), and whether the caller is a
 * [human] (a human credential carrying no seat token, exempt from seat enforcement).
 */
data class SeatCaller(
    val seat: String?,
    val runId: UUID?,
    val itemId: UUID?,
    val human: Boolean
) {
    companion object {
        /** No caller: an agent without a seat token. */
        val NONE: SeatCaller = SeatCaller(seat = null, runId = null, itemId = null, human = false)
    }
}

/**
 * Seat facts for the [StandardGates.SEAT] gate. [enforced] is false for every evaluation that has no caller (previews).
 * Cascades and structural follow-ups carry the write's caller (see [cascadeSeatAllowed]). [openRunId] is the not-closed run the item is a member of (null when none, or when
 * the loader skipped the read); [seats] are the merged seats of the item's pinned schema (empty = not seat-aware).
 */
data class SeatFacts(
    val enforced: Boolean,
    val openRunId: UUID?,
    val seats: List<SeatDefinition>,
    val caller: SeatCaller
) {
    companion object {
        val NONE: SeatFacts = SeatFacts(enforced = false, openRunId = null, seats = emptyList(), caller = SeatCaller.NONE)
    }
}
