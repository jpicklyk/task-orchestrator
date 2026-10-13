package io.github.jpicklyk.mcptask.current.application.port

import java.util.UUID

/** One seat assignment a run close ended while it was still active: the member [itemId] and the [seat] name. */
data class InFlightSeat(
    val itemId: UUID,
    val seat: String
)

/**
 * What the run service needs to know about a run's seat assignments. Seat assignments arrive with a later item; until
 * then [NONE] answers for every run: nothing is active, nobody is an assignee and nothing is ended.
 */
interface RunSeatPort {
    /** The number of seat assignments of [runId] that are still active. */
    suspend fun activeCount(runId: UUID): Int

    /** The claim-holder strings of the run's active seat assignees: the claims a close may clear. */
    suspend fun assigneeHolders(runId: UUID): Set<String>

    /** Ends every active seat assignment of [runId] with [outcome] and returns the ones it ended. Joins the caller's unit. */
    suspend fun endActive(
        runId: UUID,
        outcome: String
    ): List<InFlightSeat>

    companion object {
        /** No seat assignments exist. */
        val NONE: RunSeatPort =
            object : RunSeatPort {
                override suspend fun activeCount(runId: UUID): Int = 0

                override suspend fun assigneeHolders(runId: UUID): Set<String> = emptySet()

                override suspend fun endActive(
                    runId: UUID,
                    outcome: String
                ): List<InFlightSeat> = emptyList()
            }
    }
}
