package io.github.jpicklyk.mcptask.current.infrastructure.shutdown

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test authorship for item eeba1b12 (ShutdownCoordinator).
 *
 * Oracles are [ShutdownCoordinator]'s own KDoc (class-level `:8-14, 21-24`, [ShutdownCoordinator.addCleanupAction]
 * `:32-37 (per test-plan citation)`, [ShutdownCoordinator.awaitCompletion] / drain `finally` `:62-73`) plus the
 * contract frozen in the item's `test-plan` note: C1 every [ShutdownCoordinator.addCleanupAction] call that
 * returns normally has its action run exactly once; C2 forward registration order; C3 a throwing action never
 * aborts the rest nor propagates to the registrar; C4 [ShutdownCoordinator.initiateShutdown] is exactly-once.
 *
 * Concurrency scenarios use [CyclicBarrier]/latches to force the interleaving deterministically — never
 * `Thread.sleep` as a synchronization mechanism.
 */
class ShutdownCoordinatorTest {
    // ---- S1: forward order --------------------------------------------------------------

    @Test
    fun `S1 actions run in exact registration order`() {
        val coordinator = ShutdownCoordinator()
        val order = mutableListOf<String>()
        coordinator.addCleanupAction("A") { order.add("A") }
        coordinator.addCleanupAction("B") { order.add("B") }
        coordinator.addCleanupAction("C") { order.add("C") }

        coordinator.initiateShutdown("s1-order")

        assertEquals(listOf("A", "B", "C"), order, "actions must run in exact forward registration order")
    }

    // ---- S2: single-threaded reentrant registration during drain -----------------------

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `S2 an action that registers another action during the drain does not throw and does not skip remaining actions`() {
        val coordinator = ShutdownCoordinator()
        val order = mutableListOf<String>()
        coordinator.addCleanupAction("A") { order.add("A") }
        coordinator.addCleanupAction("reentrant") {
            order.add("reentrant")
            // Registering from inside a running cleanup action: the drain has already started,
            // so this must run immediately on this same thread, not be silently dropped, and
            // must not corrupt the outer snapshot iteration still in progress.
            coordinator.addCleanupAction("late") { order.add("late") }
        }
        coordinator.addCleanupAction("C") { order.add("C") }

        assertDoesNotThrow { coordinator.initiateShutdown("s2-reentrant") }

        assertEquals(
            listOf("A", "reentrant", "late", "C"),
            order,
            "a reentrant registration must run immediately in place, without throwing and without skipping C"
        )
    }

    // ---- S3: concurrent registration racing the drain, many iterations -----------------

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `S3 registering concurrently with initiateShutdown never throws and never loses a registration across many iterations`() {
        val iterations = 200
        val registrarThreads = 8
        val perThreadRegistrations = 50
        val executor = Executors.newFixedThreadPool(registrarThreads + 1)
        try {
            repeat(iterations) { iteration ->
                val coordinator = ShutdownCoordinator()
                val barrier = CyclicBarrier(registrarThreads + 1)
                val runCount = AtomicInteger(0)
                val returnedNormally = AtomicInteger(0)
                val exceptions = CopyOnWriteArrayList<Throwable>()

                val registrarFutures =
                    (0 until registrarThreads).map { t ->
                        executor.submit {
                            barrier.await(10, TimeUnit.SECONDS)
                            repeat(perThreadRegistrations) { i ->
                                try {
                                    coordinator.addCleanupAction("t$t-a$i") { runCount.incrementAndGet() }
                                    returnedNormally.incrementAndGet()
                                } catch (e: Throwable) {
                                    exceptions.add(e)
                                }
                            }
                        }
                    }
                val shutdownFuture =
                    executor.submit {
                        barrier.await(10, TimeUnit.SECONDS)
                        coordinator.initiateShutdown("s3-iteration-$iteration")
                    }

                (registrarFutures + shutdownFuture).forEach { it.get(20, TimeUnit.SECONDS) }

                assertTrue(exceptions.isEmpty(), "iteration $iteration: unexpected exceptions during registration: $exceptions")
                assertEquals(
                    returnedNormally.get(),
                    runCount.get(),
                    "iteration $iteration: every addCleanupAction call that returned normally must have run its action exactly once"
                )
            }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    // ---- S4: register-after-shutdown-complete -------------------------------------------

    @Test
    fun `S4 registering after shutdown has completed runs the action immediately exactly once and a second shutdown does not rerun it`() {
        val coordinator = ShutdownCoordinator()
        coordinator.initiateShutdown("s4-first")
        assertTrue(coordinator.isShutdownInitiated())
        assertTrue(coordinator.awaitCompletion(5_000))

        val counter = AtomicInteger(0)
        coordinator.addCleanupAction("late") { counter.incrementAndGet() }
        assertEquals(
            1,
            counter.get(),
            "an action registered after shutdown already completed must have run by the time addCleanupAction returns"
        )

        coordinator.initiateShutdown("s4-second")
        assertEquals(1, counter.get(), "a second initiateShutdown call must not rerun a late-registered action")
    }

    // ---- S5: late action throws ----------------------------------------------------------

    @Test
    fun `S5 a late-registered action that throws does not propagate out of addCleanupAction`() {
        val coordinator = ShutdownCoordinator()
        coordinator.initiateShutdown("s5-late-throws")

        assertDoesNotThrow {
            coordinator.addCleanupAction("throwing-late") { throw RuntimeException("boom") }
        }
    }

    // ---- S6: failure isolation ------------------------------------------------------------

    @Test
    fun `S6 a throwing action does not prevent later actions from running or awaitCompletion from succeeding`() {
        val coordinator = ShutdownCoordinator()
        val bRan = AtomicInteger(0)
        val cRan = AtomicInteger(0)
        coordinator.addCleanupAction("A-throws") { throw IllegalStateException("A failed") }
        coordinator.addCleanupAction("B") { bRan.incrementAndGet() }
        coordinator.addCleanupAction("C") { cRan.incrementAndGet() }

        assertDoesNotThrow { coordinator.initiateShutdown("s6-failure-isolation") }

        assertEquals(1, bRan.get(), "B must still run after A throws")
        assertEquals(1, cRan.get(), "C must still run after A throws")
        assertTrue(coordinator.awaitCompletion(5_000), "awaitCompletion must report completion even though an action threw")
    }

    // ---- S7: double shutdown (sequential) -------------------------------------------------

    @Test
    fun `S7 two sequential initiateShutdown calls run each action exactly once`() {
        val coordinator = ShutdownCoordinator()
        val counter = AtomicInteger(0)
        coordinator.addCleanupAction("A") { counter.incrementAndGet() }

        coordinator.initiateShutdown("s7-first")
        assertEquals(1, counter.get())
        assertTrue(coordinator.isShutdownInitiated())

        coordinator.initiateShutdown("s7-second")
        assertEquals(1, counter.get(), "the second sequential call must not rerun the action")
    }

    // ---- S8: concurrent shutdown (many threads racing initiateShutdown) ------------------

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `S8 N threads calling initiateShutdown concurrently each run every action exactly once`() {
        val threadCount = 10
        val coordinator = ShutdownCoordinator()
        val counters = (1..5).map { AtomicInteger(0) }
        counters.forEachIndexed { idx, counter -> coordinator.addCleanupAction("action-$idx") { counter.incrementAndGet() } }

        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val barrier = CyclicBarrier(threadCount)
            val futures =
                (0 until threadCount).map { t ->
                    executor.submit {
                        barrier.await(10, TimeUnit.SECONDS)
                        coordinator.initiateShutdown("s8-racer-$t")
                    }
                }
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }

        counters.forEach {
            assertEquals(
                1,
                it.get(),
                "each action must run exactly once regardless of how many threads raced to shut down"
            )
        }
        assertTrue(coordinator.isShutdownInitiated())
        assertTrue(coordinator.awaitCompletion(5_000))
    }

    // ---- S9: startup-window stand-in (one-at-a-time registration racing a shutdown handler) --

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `S9 a startup thread registering one action at a time never loses a registration to a concurrent shutdown handler`() {
        val iterations = 100
        val registrationsPerIteration = 30
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(iterations) { iteration ->
                val coordinator = ShutdownCoordinator()
                val barrier = CyclicBarrier(2)
                val counter = AtomicInteger(0)

                val startupFuture =
                    executor.submit {
                        barrier.await(10, TimeUnit.SECONDS)
                        repeat(registrationsPerIteration) { i ->
                            coordinator.addCleanupAction("startup-$iteration-$i") { counter.incrementAndGet() }
                        }
                    }
                val handlerFuture =
                    executor.submit {
                        barrier.await(10, TimeUnit.SECONDS)
                        coordinator.initiateShutdown("s9-iteration-$iteration")
                    }

                startupFuture.get(15, TimeUnit.SECONDS)
                handlerFuture.get(15, TimeUnit.SECONDS)

                assertEquals(
                    registrationsPerIteration,
                    counter.get(),
                    "iteration $iteration: every startup registration must have run exactly once, whichever side won the race"
                )
            }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    // ---- S10: empty action list ------------------------------------------------------------

    @Test
    fun `S10 shutdown with zero registered actions completes and reports completion`() {
        val coordinator = ShutdownCoordinator()
        assertDoesNotThrow { coordinator.initiateShutdown("s10-empty") }
        assertTrue(coordinator.awaitCompletion(5_000))
    }

    // ---- S11: awaitCompletion before any shutdown ------------------------------------------

    @Test
    fun `S11 awaitCompletion returns false before shutdown is ever initiated`() {
        val coordinator = ShutdownCoordinator()
        assertFalse(coordinator.awaitCompletion(50))
        assertFalse(coordinator.isShutdownInitiated())
    }

    // ---- Probes: duplicates / ordering, boundary 1 -----------------------------------------

    @Test
    fun `duplicate action names run every registration, not deduplicated, preserving order`() {
        val coordinator = ShutdownCoordinator()
        val order = mutableListOf<String>()
        coordinator.addCleanupAction("dup") { order.add("first") }
        coordinator.addCleanupAction("dup") { order.add("second") }
        coordinator.addCleanupAction("dup") { order.add("third") }

        coordinator.initiateShutdown("dup-name-probe")

        assertEquals(
            listOf("first", "second", "third"),
            order,
            "name is an opaque log label, never a dedup key"
        )
    }

    @Test
    fun `the same lambda instance registered twice under different names runs twice`() {
        val coordinator = ShutdownCoordinator()
        val counter = AtomicInteger(0)
        val sharedLambda: () -> Unit = { counter.incrementAndGet() }

        coordinator.addCleanupAction("a", sharedLambda)
        coordinator.addCleanupAction("b", sharedLambda)

        coordinator.initiateShutdown("dup-lambda-probe")

        assertEquals(2, counter.get(), "a shared lambda instance registered twice must run once per registration")
    }

    @Test
    fun `boundary a single registered action runs exactly once`() {
        val coordinator = ShutdownCoordinator()
        val counter = AtomicInteger(0)
        coordinator.addCleanupAction("only") { counter.incrementAndGet() }

        coordinator.initiateShutdown("boundary-one")

        assertEquals(1, counter.get())
    }
}
