package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.model.run.Run
import io.github.jpicklyk.mcptask.current.model.run.RunStatus
import io.github.jpicklyk.mcptask.current.telemetry.ObservedRunTokens
import java.time.Instant
import java.util.UUID

/**
 * Persistence of runs (`runs`) and their membership (`run_items`). Every method joins the caller's unit; the writes
 * belong to `RunService` only. A database failure is thrown.
 */
interface RunStore :
    RunReads,
    OpenRunCount,
    ObservedRunTokens {
    /** Inserts [run] (no members) and returns it as stored. */
    suspend fun create(run: Run): Run

    /**
     * Writes every mutable field of [run] when the stored version is still [expectedVersion], bumping the stored
     * version to `expectedVersion + 1`. Returns the run as stored, or null when the run is gone or its version moved.
     */
    suspend fun update(
        run: Run,
        expectedVersion: Long
    ): Run?

    /**
     * Sets `coordinator_seen_at` of [runId] to [at] WITHOUT bumping the version (liveness is not a change an orchestrator's
     * `expectedVersion` should conflict with). A missing run is a no-op.
     */
    suspend fun touchCoordinator(
        runId: UUID,
        at: Instant
    )

    /**
     * Adds [tokens] to `observed_tokens` of [runId] WITHOUT bumping the version (as [touchCoordinator]: telemetry is not a
     * change an orchestrator's `expectedVersion` should conflict with). A missing run is a no-op; a closed run still adds.
     */
    override suspend fun addObservedTokens(
        runId: UUID,
        tokens: Long
    )

    /** The member item ids of [runId], oldest membership first. */
    suspend fun members(runId: UUID): List<UUID>

    /** Adds [itemIds] as members of [runId] at [addedAt]; ids already members are skipped. Returns how many were added. */
    suspend fun addMembers(
        runId: UUID,
        itemIds: Collection<UUID>,
        addedAt: Instant
    ): Int

    /** Removes [itemIds] from the members of [runId]; non-members are skipped. Returns how many were removed. */
    suspend fun removeMembers(
        runId: UUID,
        itemIds: Collection<UUID>
    ): Int

    /** For each of [itemIds] that is a member of a run that is not closed, that run's id. Others are absent. */
    suspend fun openRunByItem(itemIds: Set<UUID>): Map<UUID, UUID>

    /**
     * The runs matching every given filter, newest first (`openedAt` descending, then `id` ascending): those on a root
     * in [rootIds] (null means any root; an empty set matches nothing), with [status], and closed at or after
     * [closedSince] (a run with no `closedAt` never matches).
     */
    suspend fun list(
        rootIds: Set<UUID>? = null,
        status: RunStatus? = null,
        closedSince: Instant? = null
    ): List<Run>

    /**
     * The ids of the runs whose id, as 32 lower-case hex digits without dashes, starts with [prefix] (compared ignoring
     * case), in ascending id order, at most [limit] of them. A prefix that is not hex, or longer than 32 digits, matches
     * nothing.
     */
    suspend fun findIdsByPrefix(
        prefix: String,
        limit: Int = 2
    ): List<UUID>
}
