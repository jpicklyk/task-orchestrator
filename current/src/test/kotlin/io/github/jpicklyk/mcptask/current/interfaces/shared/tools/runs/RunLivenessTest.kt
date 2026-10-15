package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunLiveness
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item fca8ce36, R25) for the one stalled rule of `RunLiveness`: the pure predicate every
 * seat and the coordinator is judged by. Scenarios S8 (the strict boundary, the run's own threshold), S9 (a seat never seen is
 * judged by its assignment instant) and the exactness decision D2; the wire-level boundaries (S8a, S3) live in `RunsFrontierTest`.
 *
 * Oracles (never read from a run): the R25 formula of the core plan, "stalled = now - lastSeenAt > stallAfterSeconds" (strict
 * greater-than, exact duration, no truncation to whole seconds); task-scope Build 1 and decisions D1 (a seat is last seen at its
 * `lastSeenAt`, else its `assignedAt`), D2 (exact comparison) and D5 (a closed run is never stalled); the run stallAfterSeconds
 * is pinned per run and is the only threshold, never `Run.DEFAULT_STALL_AFTER_SECONDS`. Every boundary is written as the literal
 * instants `now - 120 s` and `now - 120 s - 1 ms` against a threshold of 120.
 *
 * Negative-assertion conditions: every "not stalled" assertion has a twin one millisecond older that is stalled (the fixture that
 * would fail it under a `>=` or a truncated comparison); "null is not stalled" is paired with the same call on a non-null instant.
 *
 * Domain invariants satisfied by construction: a closed run carries a terminal reason, a closing instant and a closer; a coordinator
 * seat has no item and an item seat has one; both seats are active, so no outcome and no end instant.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
class RunLivenessTest {
    private val now: Instant = Instant.parse("2026-10-10T12:00:00Z")
    private val stall = 120

    private fun ago(
        seconds: Long,
        plusMillis: Long = 0
    ): Instant = now.minusSeconds(seconds).minusMillis(plusMillis)

    private fun run(
        stallAfterSeconds: Int = stall,
        coordinatorSeenAt: Instant?,
        status: RunStatus = RunStatus.OPEN
    ): Run =
        Run(
            rootId = UUID.randomUUID(),
            status = status,
            terminalReason = if (status == RunStatus.CLOSED) RunTerminalReason.COMPLETED else null,
            openedBy = "api:opener",
            openedAt = now.minusSeconds(10_000),
            closedBy = if (status == RunStatus.CLOSED) "api:opener" else null,
            closedAt = if (status == RunStatus.CLOSED) now.minusSeconds(5_000) else null,
            coordinatorSeenAt = coordinatorSeenAt,
            stallAfterSeconds = stallAfterSeconds
        )

    private fun seat(
        assignedAt: Instant,
        lastSeenAt: Instant?
    ): RunSeat =
        RunSeat(
            runId = UUID.randomUUID(),
            itemId = UUID.randomUUID(),
            seat = "implementer",
            attempt = 1,
            principalId = "api:seat",
            tokenHash = "0".repeat(64),
            assignedAt = assignedAt,
            lastSeenAt = lastSeenAt
        )

    // ------------------------------------------------------------------ S8 the predicate

    @Test
    fun `S8 exactly the threshold is not stalled and one millisecond more is`() {
        assertFalse(RunLiveness.isStalled(ago(120), now, stall), "120 s ago with a threshold of 120 is not stalled (strict >)")
        assertTrue(RunLiveness.isStalled(ago(120, 1), now, stall), "120 s and 1 ms ago is stalled")
        assertFalse(RunLiveness.isStalled(ago(119, 999), now, stall), "119.999 s ago is not stalled")
        assertFalse(RunLiveness.isStalled(now, now, stall), "seen right now is not stalled")
    }

    @Test
    fun `S8 the comparison is exact, not truncated to whole seconds`() {
        // A comparison on whole seconds would read 1.001 s as 1 s and call it not stalled with a threshold of 1.
        assertTrue(RunLiveness.isStalled(now.minus(Duration.ofMillis(1_001)), now, 1))
        assertFalse(RunLiveness.isStalled(now.minus(Duration.ofMillis(1_000)), now, 1))
        assertFalse(RunLiveness.isStalled(now.minus(Duration.ofMillis(999)), now, 1))
        // and at a larger threshold the sub-second part still decides
        assertTrue(RunLiveness.isStalled(now.minus(Duration.ofSeconds(600).plusNanos(1_000_000)), now, 600))
        assertFalse(RunLiveness.isStalled(now.minus(Duration.ofSeconds(600)), now, 600))
    }

    @Test
    fun `S8 a never-seen instant is not stalled, and the same call on an old instant is`() {
        assertFalse(RunLiveness.isStalled(null, now, stall), "no sign of life recorded: not stalled")
        assertFalse(RunLiveness.isStalled(null, now, 1), "whatever the threshold")
        assertTrue(RunLiveness.isStalled(ago(10_000), now, stall), "control: an old instant is stalled")
    }

    @Test
    fun `S8 the threshold is the argument - the same instants read differently at 120 and at 600`() {
        val lastSeen = ago(121)
        assertTrue(RunLiveness.isStalled(lastSeen, now, 120))
        assertFalse(RunLiveness.isStalled(lastSeen, now, 600), "the default of 600 is never consulted")
        assertFalse(RunLiveness.isStalled(lastSeen, now, 121), "121 s ago with a threshold of 121: exactly the threshold")
    }

    @Test
    fun `S8 probe a last-seen instant in the future is not stalled, and the largest threshold does not overflow`() {
        assertFalse(RunLiveness.isStalled(now.plusSeconds(5), now, stall), "a clock step backwards must not read as stalled")
        assertFalse(RunLiveness.isStalled(ago(1_000_000), now, Int.MAX_VALUE), "Int.MAX_VALUE seconds is about 68 years")
        assertTrue(RunLiveness.isStalled(now.minusSeconds(Int.MAX_VALUE.toLong() + 1), now, Int.MAX_VALUE))
    }

    // ------------------------------------------------------------------ S9 the seat reference

    @Test
    fun `S9 a seat is last seen at lastSeenAt, and at assignedAt only when it never touched anything`() {
        val seen = seat(assignedAt = ago(5_000), lastSeenAt = ago(30))
        assertEquals(ago(30), RunLiveness.seatReference(seen), "lastSeenAt wins over an older assignedAt")

        val seenBeforeAssignment = seat(assignedAt = ago(30), lastSeenAt = ago(5_000))
        assertEquals(ago(5_000), RunLiveness.seatReference(seenBeforeAssignment), "lastSeenAt wins even when it is the older instant")

        val never = seat(assignedAt = ago(77), lastSeenAt = null)
        assertEquals(ago(77), RunLiveness.seatReference(never), "never touched: the assignment instant")
    }

    @Test
    fun `S9 a seat that was seen recently is not stalled however long ago it was assigned`() {
        val seen = seat(assignedAt = ago(100_000), lastSeenAt = ago(10))
        assertFalse(RunLiveness.isSeatStalled(seen, now, stall))
        assertTrue(
            RunLiveness.isSeatStalled(seat(assignedAt = ago(100_000), lastSeenAt = null), now, stall),
            "control: never seen, long ago"
        )
    }

    @Test
    fun `S9 a never-seen seat is judged by its assignment instant with the strict boundary`() {
        assertFalse(RunLiveness.isSeatStalled(seat(assignedAt = ago(120), lastSeenAt = null), now, stall), "assigned exactly 120 s ago")
        assertTrue(RunLiveness.isSeatStalled(seat(assignedAt = ago(120, 1), lastSeenAt = null), now, stall), "assigned 120 s and 1 ms ago")
        assertTrue(RunLiveness.isSeatStalled(seat(assignedAt = ago(121), lastSeenAt = null), now, stall), "assigned 121 s ago")
    }

    @Test
    fun `S8 a seat with lastSeenAt is judged by it with the strict boundary, at the threshold given`() {
        assertFalse(RunLiveness.isSeatStalled(seat(assignedAt = ago(9_999), lastSeenAt = ago(120)), now, stall))
        assertTrue(RunLiveness.isSeatStalled(seat(assignedAt = ago(9_999), lastSeenAt = ago(120, 1)), now, stall))
        assertFalse(RunLiveness.isSeatStalled(seat(assignedAt = ago(9_999), lastSeenAt = ago(120, 1)), now, 121), "another threshold")
    }

    // ------------------------------------------------------------------ S8a the coordinator

    @Test
    fun `S8a the coordinator is stalled strictly after the run own threshold, never the default`() {
        assertFalse(RunLiveness.isCoordinatorStalled(run(coordinatorSeenAt = ago(120)), now), "exactly 120 s")
        assertTrue(RunLiveness.isCoordinatorStalled(run(coordinatorSeenAt = ago(120, 1)), now), "120 s and 1 ms")
        // the default is 600: a run pinned at 120 is stalled at 121 s, a run pinned at 900 is not stalled at 700 s
        assertTrue(RunLiveness.isCoordinatorStalled(run(stallAfterSeconds = 120, coordinatorSeenAt = ago(121)), now))
        assertFalse(RunLiveness.isCoordinatorStalled(run(stallAfterSeconds = 900, coordinatorSeenAt = ago(700)), now))
        assertTrue(RunLiveness.isCoordinatorStalled(run(stallAfterSeconds = 900, coordinatorSeenAt = ago(901)), now))
    }

    @Test
    fun `S8a a coordinator never seen is not stalled`() {
        assertFalse(RunLiveness.isCoordinatorStalled(run(coordinatorSeenAt = null), now))
        assertTrue(RunLiveness.isCoordinatorStalled(run(coordinatorSeenAt = ago(10_000)), now), "control: seen long ago")
    }

    @Test
    fun `S10 a closed run coordinator is never stalled, the identical open run one is`() {
        val seenLongAgo = ago(100_000)
        assertTrue(RunLiveness.isCoordinatorStalled(run(coordinatorSeenAt = seenLongAgo, status = RunStatus.OPEN), now), "control: open")
        assertFalse(RunLiveness.isCoordinatorStalled(run(coordinatorSeenAt = seenLongAgo, status = RunStatus.CLOSED), now))
    }
}
