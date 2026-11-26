package io.github.jpicklyk.mcptask.current.lifecycle.internal.domain

import io.github.jpicklyk.mcptask.current.model.schema.SeatCaller
import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition
import java.util.UUID

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
