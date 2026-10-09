package io.github.jpicklyk.mcptask.current.infrastructure

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.CLAIM_T
import io.github.jpicklyk.mcptask.current.application.service.ClaimRig
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.service.ExpirySweep
import io.github.jpicklyk.mcptask.current.application.service.ok
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 5cd1086c) for [ExpirySweeper], scenario S15 of the frozen test-plan.
 *
 * Oracles: task-scope "Sweep": the sweeper mirrors IdempotencyPruner (a pass at start, then one pass every interval,
 * one write unit per pass, a failing pass is logged and never fatal) and stop() ends the loop; sweep rows carry no
 * actor (null principal); plan 3.7 / 3.12 (hourly sweep of lapsed claims and leases, expiry at or before the unit
 * instant). The default interval is one hour (declared constructor default). Lapsed state is made with the settable
 * clock the unit of work is bound to, so no test sleeps for a claim to expire.
 *
 * Vacuity: the nothing-after-stop assertion is paired with a control that the same kind of lapsed claim IS reported
 * while the sweeper runs.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ExpirySweeperTest {
    private val clock = SettableClock(CLAIM_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private fun rig() = ClaimRig(sqlite.db, clock)

    private suspend fun waitUntil(
        timeoutMs: Long = 15_000,
        condition: suspend () -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            delay(25)
        }
        return condition()
    }

    private suspend fun ClaimRig.expiredRows() = rowsAfter(0L).filter { it.type == "claim.expired" || it.type == "lease.expired" }

    // S15 -- one pass at start
    @Test
    fun `S15 start runs one pass before returning and the sweep rows have a null principal`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S15 lapsed claim")
            val h = rig.seed("S15 lapsed lease")
            rig.service.claim(i.id, "agent-a", 60).ok()
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok()
            clock.set(CLAIM_T.plusSeconds(60))
            val sweeper = ExpirySweeper(rig.uow, rig.service)

            sweeper.start()
            try {
                val rows = rig.expiredRows()
                assertEquals(listOf("claim.expired", "lease.expired"), rows.map { it.type }.sorted(), "the startup pass ran: $rows")
                assertTrue(rows.all { it.principalId == null }, "sweep rows carry no actor: $rows")
            } finally {
                sweeper.stop()
            }
        }

    @Test
    fun `S15 sweepOnce runs one pass and reports what it recorded`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S15 once")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(CLAIM_T.plusSeconds(60))
            val sweeper = ExpirySweeper(rig.uow, rig.service)

            val first = sweeper.sweepOnce()
            val second = sweeper.sweepOnce()

            assertEquals(ExpirySweep(claimsExpired = 1, leasesExpired = 0), first)
            assertEquals(ExpirySweep(0, 0), second, "a second pass finds nothing new")
            assertEquals(1, rig.expiredRows().size)
            assertNull(rig.expiredRows().single().principalId)
        }

    // S15 -- periodic passes and stop()
    @Test
    fun `S15 the loop sweeps again on the interval and stop ends it`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S15 periodic")
            rig.service.claim(i.id, "agent-a", 60).ok()
            val sweeper = ExpirySweeper(rig.uow, rig.service, interval = Duration.ofMillis(50))

            sweeper.start() // the startup pass finds nothing: the claim is active at T
            try {
                assertEquals(emptyList(), rig.expiredRows(), "control: nothing is lapsed yet")
                clock.set(CLAIM_T.plusSeconds(60))
                assertTrue(waitUntil { rig.expiredRows().size == 1 }, "a periodic pass must report the claim that lapsed after startup")
            } finally {
                sweeper.stop()
            }

            // after stop(): the same kind of lapsed claim is no longer reported (control: it was reported while running)
            val later = rig.seed("S15 after stop")
            rig.service.claim(later.id, "agent-b", 60).ok()
            clock.set(CLAIM_T.plusSeconds(180))
            delay(800)
            assertEquals(1, rig.expiredRows().size, "nothing may run after stop()")
        }

    /** Delegates to the real unit of work but fails every write with an exception, counting the attempts. */
    private class FailingWrites(
        private val real: UnitOfWork,
        val attempts: AtomicInteger
    ) : UnitOfWork by real {
        override suspend fun <T> write(
            op: String,
            block: suspend WriteScope.() -> Outcome<T>
        ): Outcome<T> {
            attempts.incrementAndGet()
            error("simulated store fault")
        }
    }

    // task-scope "Sweep": a failing pass is logged and never fatal
    @Test
    fun `S15 a failing pass does not throw out of start, the loop keeps retrying, and stop completes`(): Unit =
        runBlocking {
            val rig = rig()
            val attempts = AtomicInteger(0)
            val failing = FailingWrites(rig.uow, attempts)
            val sweeper =
                ExpirySweeper(
                    unitOfWork = failing,
                    claimService = ClaimService(rig.provider, failing),
                    interval = Duration.ofMillis(50)
                )

            sweeper.start() // must not throw although the startup pass fails
            try {
                assertTrue(waitUntil { attempts.get() >= 3 }, "the loop must survive failures and attempt again: ${attempts.get()}")
            } finally {
                sweeper.stop()
            }

            val afterStop = attempts.get()
            delay(500)
            assertEquals(afterStop, attempts.get(), "no attempt may run after stop()")
        }
}
