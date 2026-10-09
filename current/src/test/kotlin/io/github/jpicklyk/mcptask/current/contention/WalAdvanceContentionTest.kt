package io.github.jpicklyk.mcptask.current.contention

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Advance races over a file-backed WAL database through the production composition (item 36c719db, closes AR-53).
 * Each test runs sequentially first as the oracle, then 20 times with real threads and a start latch.
 * Asserts the 3.x wire codes of `advance_item`, not internal failure classes.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class WalAdvanceContentionTest {
    @Test
    fun `A1 three racers complete one item - one applies and the losers are invalid_transition`() =
        runScenario(REPEATS) { WalScenarios.a1CompleteRace(it, racers = 3) }

    @Test
    fun `A2 two racers start one item - the loser is gate_blocked on the committed state`() =
        runScenario(REPEATS) {
            WalScenarios.a2StartRace(it)
        }

    @Test
    fun `S1 four siblings complete together - exactly one entry carries the parent cascade`() =
        runScenario(REPEATS) { WalScenarios.s1SiblingCascade(it, children = 4) }

    @Test
    fun `S1b four siblings under a grandparent - the winner carries both cascades`() =
        runScenario(REPEATS) { WalScenarios.s1bGrandparentCascade(it, children = 4) }

    @Test
    fun `L1 two items race one exclusive lease - the loser is resource_unavailable with an exact retryAfterMs`() =
        runScenario(REPEATS) { WalScenarios.l1LeaseRace(it) }

    @Test
    fun `L2 the holder completes while a contender starts - never two holders`() =
        runScenario(REPEATS) { WalScenarios.l2HolderReleaseVersusContender(it) }

    private companion object {
        const val REPEATS = 20
    }
}
