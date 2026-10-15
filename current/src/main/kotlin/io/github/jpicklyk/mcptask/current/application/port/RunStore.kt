package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import java.time.Instant
import java.util.UUID

/**
 * Persistence of runs (`runs`) and their membership (`run_items`). Every method joins the caller's unit; the writes
 * belong to `RunService` only. A database failure is thrown.
 */
interface RunStore {
    /** Inserts [run] (no members) and returns it as stored. */
    suspend fun create(run: Run): Run

    /** The run [id], or null when there is none. */
    suspend fun getById(id: UUID): Run?

    /**
     * Writes every mutable field of [run] when the stored version is still [expectedVersion], bumping the stored
     * version to `expectedVersion + 1`. Returns the run as stored, or null when the run is gone or its version moved.
     */
    suspend fun update(
        run: Run,
        expectedVersion: Long
    ): Run?

    /**
     * Sets `coordinator_seen_at` of [runId] to [at] WITHOUT bumping the version (liveness is not a change a coordinator's
     * `expectedVersion` should conflict with). A missing run is a no-op.
     */
    suspend fun touchCoordinator(
        runId: UUID,
        at: Instant
    )

    /**
     * Adds [tokens] to `observed_tokens` of [runId] WITHOUT bumping the version (as [touchCoordinator]: telemetry is not a
     * change a coordinator's `expectedVersion` should conflict with). A missing run is a no-op; a closed run still adds.
     */
    suspend fun addObservedTokens(
        runId: UUID,
        tokens: Long
    )

    /**
     * Sets `reported_tokens` and `reported_cost_micro` of [runId] to the summed seat reports [tokens] and [costMicroUsd],
     * WITHOUT bumping the version. A missing run is a no-op.
     */
    suspend fun setReportedUsage(
        runId: UUID,
        tokens: Long,
        costMicroUsd: Long
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

    /** The number of runs on root [rootId] that are not closed (open or paused). */
    suspend fun countNotClosedByRoot(rootId: UUID): Int

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
