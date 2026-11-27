package io.github.jpicklyk.mcptask.current.model.schema

import java.util.UUID

/**
 * Who sends a transition or a note write, as seat enforcement (R23) sees it: the seat token's [seat], [runId] and member
 * [itemId] (all null without a token; [itemId] is null for the run-token row (`coordinator`)), and whether the caller is a
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
