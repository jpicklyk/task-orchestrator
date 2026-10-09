package io.github.jpicklyk.mcptask.current.contention

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The same races with the racers split across TWO `DatabaseManager`s on one WAL file (item 36c719db): a second manager has
 * its own writer mutex and pool, so this is the real SQLite-level contention path (IMMEDIATE BEGIN, busy_timeout, unit
 * BUSY retry) that a single manager serializes away. Serial: it is timing- and lock-sensitive.
 */
@Tag("serial")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class WalCrossProcessContentionTest {
    @Test
    fun `A1 four racers on two managers complete one item`() =
        runScenario(REPEATS, twoManagers = true) {
            WalScenarios.a1CompleteRace(it, racers = 4)
        }

    @Test
    fun `S1 four siblings on two managers complete together`() =
        runScenario(REPEATS, twoManagers = true) { WalScenarios.s1SiblingCascade(it, children = 4) }

    @Test
    fun `L1 two items on two managers race one exclusive lease`() =
        runScenario(REPEATS, twoManagers = true) { WalScenarios.l1LeaseRace(it) }

    @Test
    fun `C1 eight agents on two managers race one claim`() =
        runScenario(REPEATS, twoManagers = true) {
            WalScenarios.c1ClaimRace(it, agents = 8)
        }

    @Test
    fun `K1 eight holders on two managers race one lease key`() =
        runScenario(REPEATS, twoManagers = true) { WalScenarios.k1LeaseAcquireRace(it, holders = 8) }

    private companion object {
        const val REPEATS = 8
    }
}
