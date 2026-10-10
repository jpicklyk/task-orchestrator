package io.github.jpicklyk.mcptask.current.contention

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Reparent atomicity over a file-backed WAL database through the production composition (item 36c719db, carry-in P15
 * R11). These pin the subtree restamp inside the reparent's unit independently of the implementer-era fault suites
 * (P5bWriteUnitToolsTest S3, ManageItemsParentPlacementInTxnTest S16, ManageItemsWritePathFailureTest S11,
 * ItemWriteRoutesFailurePathTest S12), so they survive those suites' retirement.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class WalReparentContentionTest {
    @Test
    fun `R1 a reparent races a create under and a start of a descendant - the subtree is never seen half-restamped`() =
        runScenario(REPEATS) { WalReparentScenarios.r1ReparentVersusAdvanceAndCreate(it) }

    @Test
    fun `R2 a fault inside the subtree restamp rolls the whole reparent back`() {
        WalFixture.open(WalPass.FORWARD, twoManagers = false).use { fx -> runBlocking { WalReparentScenarios.r2RestampFaultRollsBack(fx) } }
    }

    private companion object {
        const val REPEATS = 20
    }
}
