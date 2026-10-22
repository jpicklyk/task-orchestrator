package io.github.jpicklyk.mcptask.current.application.port

import java.util.UUID

/** One seat assignment a run close ended while it was still active: the member [itemId] and the [seat] name. */
data class InFlightSeat(
    val itemId: UUID,
    val seat: String
)

/**
 * The claim holder pair of one active seat assignee: the principal the assignment's token is bound to and the
 * assignment id. A member claim is the assignee's when BOTH columns match (`claimed_by`, `claim_assignment_id`).
 */
data class AssigneeHolder(
    val principalId: String,
    val assignmentId: UUID
)

/**
 * What the run service needs to know about a run's seat assignments. [NONE] answers for every run: nothing is active,
 * nobody is an assignee and nothing is ended (a run service built without seats, in tests).
 *
 * The run-scoped `coordinator` pseudo-seat is not a member seat: [activeCount] does not count it and [endActive] never
 * reports it, though it ends it with the others.
 */
interface RunSeatPort {
    /** The number of member-seat assignments of [runId] that are still active (the coordinator is not counted). */
    suspend fun activeCount(runId: UUID): Int

    /** The holder pairs of the run's active seat assignees (coordinator included): the claims a close may clear. */
    suspend fun assigneeHolders(runId: UUID): Set<AssigneeHolder>

    /**
     * Ends every active assignment of [runId] (the coordinator included) with [outcome], a `run_seats` outcome wire
     * value, and returns the member seats it ended. Joins the caller's unit.
     */
    suspend fun endActive(
        runId: UUID,
        outcome: String
    ): List<InFlightSeat>

    companion object {
        /** No seat assignments exist. */
        val NONE: RunSeatPort =
            object : RunSeatPort {
                override suspend fun activeCount(runId: UUID): Int = 0

                override suspend fun assigneeHolders(runId: UUID): Set<AssigneeHolder> = emptySet()

                override suspend fun endActive(
                    runId: UUID,
                    outcome: String
                ): List<InFlightSeat> = emptyList()
            }
    }
}
