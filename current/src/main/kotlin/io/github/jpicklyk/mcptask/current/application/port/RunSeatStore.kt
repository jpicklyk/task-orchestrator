package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatLink
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatOutcome
import java.time.Instant
import java.util.UUID

/** The usage summed over every assignment of one run (ended ones included): [tokens] and [costMicroUsd] (micro-USD). */
data class RunUsageTotals(
    val tokens: Long,
    val costMicroUsd: Long
)

/**
 * Persistence of seat assignments (`run_seats`). Every method joins the caller's unit; the writes belong to
 * `SeatService` (and, through [RunSeatPort], to the run close). A database failure is thrown.
 */
interface RunSeatStore : RunSeatPort {
    /** Inserts [seat] and returns it as stored. */
    suspend fun create(seat: RunSeat): RunSeat

    suspend fun getById(id: UUID): RunSeat?

    /** The assignment whose token hashes to [tokenHash] (active or ended), or null. */
    suspend fun findByTokenHash(tokenHash: String): RunSeat?

    /** The active assignment of [seat] on [itemId] in [runId], or null. */
    suspend fun findActive(
        runId: UUID,
        itemId: UUID,
        seat: String
    ): RunSeat?

    /** The highest attempt of [seat] on [itemId] in [runId] over every row, ended ones included; 0 when there is none. */
    suspend fun maxAttempt(
        runId: UUID,
        itemId: UUID,
        seat: String
    ): Int

    /**
     * Ends the assignment [id] when it is still active: [outcome], [endedAt], and [summary]/[links] when given.
     * Returns the stored row, or null when it was not active (nothing written).
     */
    suspend fun end(
        id: UUID,
        outcome: RunSeatOutcome,
        endedAt: Instant,
        summary: String? = null,
        links: List<RunSeatLink> = emptyList()
    ): RunSeat?

    /** Sets `last_seen_at` of [id] to [at]. */
    suspend fun touch(
        id: UUID,
        at: Instant
    )

    /**
     * Keeps the higher of the stored and the reported value for each of [tokens] and [costMicro] (a null field is left
     * as stored). Returns the stored row, or null when there is none.
     */
    suspend fun recordUsage(
        id: UUID,
        tokens: Long?,
        costMicro: Long?
    ): RunSeat?

    /** The sum over every assignment of [runId], ended ones and the coordinator included, of its kept usage. */
    suspend fun usageTotals(runId: UUID): RunUsageTotals

    /**
     * The assignments of [runId], oldest first (`assigned_at`, then id): only those on [itemId] when given, and only
     * active ones unless [includeEnded].
     */
    suspend fun list(
        runId: UUID,
        itemId: UUID? = null,
        includeEnded: Boolean = false
    ): List<RunSeat>
}
