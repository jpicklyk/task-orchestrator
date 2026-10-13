package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.Run
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
}
