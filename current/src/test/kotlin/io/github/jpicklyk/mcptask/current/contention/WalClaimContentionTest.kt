package io.github.jpicklyk.mcptask.current.contention

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Claim and lease races through `ClaimService` over a file-backed WAL database (item 36c719db, closes AR-53).
 * Each test runs sequentially first as the oracle, then 20 times with real threads and a start latch.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class WalClaimContentionTest {
    @Test
    fun `C1 six agents claim one item - one wins and every loser is AlreadyClaimed with retryAfterMs of ttl`() =
        runScenario(REPEATS) { WalScenarios.c1ClaimRace(it, agents = 6) }

    @Test
    fun `C2 the holder releases twice at once - one Success and one NotClaimedByYou`() =
        runScenario(REPEATS) { WalScenarios.c2ReleaseRace(it) }

    @Test
    fun `C3 a holder moving to Y races another agent claiming X - A always releases X`() =
        runScenario(REPEATS) { WalScenarios.c3SupersedeVersusClaim(it) }

    @Test
    fun `C4 a claim races an advance by another actor - either order is exact`() =
        runScenario(REPEATS) {
            WalScenarios.c4ClaimVersusAdvance(it)
        }

    @Test
    fun `K1 five holders acquire one lease key - one Success and each loser Contended with retryAfterMs of ttl`() =
        runScenario(REPEATS) { WalScenarios.k1LeaseAcquireRace(it, holders = 5) }

    private companion object {
        const val REPEATS = 20
    }
}
