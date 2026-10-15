package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Duration
import java.time.Instant

/**
 * The one stalled rule of a run (D21 liveness, derived view only): something is stalled when the time since it was last
 * seen is STRICTLY longer than the run's pinned [Run.stallAfterSeconds]. Never read from config or
 * [Run.DEFAULT_STALL_AFTER_SECONDS] at read time; nothing is written, ended or raised when a seat stalls.
 */
object RunLiveness {
    /**
     * True iff [lastSeen] is known and `now - lastSeen` is strictly longer than [stallAfterSeconds] seconds, compared as
     * exact durations (no truncation to whole seconds). A null [lastSeen] is never stalled.
     */
    fun isStalled(
        lastSeen: Instant?,
        now: Instant,
        stallAfterSeconds: Int
    ): Boolean = lastSeen != null && Duration.between(lastSeen, now) > Duration.ofSeconds(stallAfterSeconds.toLong())

    /** The instant a seat assignment was last seen: its [RunSeat.lastSeenAt], or [RunSeat.assignedAt] when it never touched anything. */
    fun seatReference(seat: RunSeat): Instant = seat.lastSeenAt ?: seat.assignedAt

    /** True iff [seat] is active and stalled under the threshold [stallAfterSeconds] of its run ([seatReference]). */
    fun isSeatStalled(
        seat: RunSeat,
        now: Instant,
        stallAfterSeconds: Int
    ): Boolean = seat.isActive && isStalled(seatReference(seat), now, stallAfterSeconds)

    /** True iff [run] is not closed and its coordinator ([Run.coordinatorSeenAt]) is stalled. */
    fun isCoordinatorStalled(
        run: Run,
        now: Instant
    ): Boolean = run.status != RunStatus.CLOSED && isStalled(run.coordinatorSeenAt, now, run.stallAfterSeconds)
}
