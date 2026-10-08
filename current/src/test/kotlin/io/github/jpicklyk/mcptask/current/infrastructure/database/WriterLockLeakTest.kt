package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Round-3 regression for item 9343ad8d. Oracle: plan section 7 row 3 ("acquisition recorded ... so a timeout can
 * never leak the lock") and the kotlinx asynchronous-timeout rule (a timeout may fire after the lock was granted).
 *
 * A waiter write unit's bounded wait for the writer times out at (nearly) the same instant the holder releases. The
 * release instant is swept across the waiter's deadline so that some iterations land the grant exactly on the
 * timeout. Either outcome is acceptable for the waiter (Ok or UNAVAILABLE); after EVERY iteration the writer must be
 * free, observed by a fresh write unit completing Ok promptly. A leaked lock would make that unit time out UNAVAILABLE.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class WriterLockLeakTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val dm get() = db.databaseManager

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `writer is free after a bounded wait times out at the instant the holder releases`(): Unit =
        runBlocking {
            val uow = db.uow()
            // CPU contention stretches the scheduling gap between a grant and the waiter's timeout check, widening the
            // race window the same way a cold JIT does.
            val stop =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val noise =
                List(Runtime.getRuntime().availableProcessors()) {
                    Thread {
                        while (!stop.get()) Thread.onSpinWait()
                    }.apply {
                        isDaemon = true
                        start()
                    }
                }
            val iterations = 2_800
            val waiterCount = 128
            var okWaiters = 0
            var shedWaiters = 0
            try {
                val rnd =
                    java.util.concurrent.ThreadLocalRandom
                        .current()
                val narrowJitterNanos = 20_000L
                val wideJitterNanos = 800_000L
                val stepNanos = 10_000L
                var controlNanos = 400_000L

                repeat(iterations) { i ->
                    // Vary the waiter deadline so the timer tick it lands on differs between iterations.
                    val shortDeadlineMs = 1L + (i % 3)
                    dm.units.deadline = shortDeadlineMs.milliseconds
                    val held = CompletableDeferred<Unit>()
                    val waiterStart = AtomicLong(0L)
                    // Feedback control keeps the release instant hugging the waiter's actual deadline: release later after
                    // a grant, earlier after a timeout, with jitter, so the grant keeps straddling the timeout.
                    val spread = if (i % 2 == 0) narrowJitterNanos else wideJitterNanos
                    val offsetNanos = controlNanos + rnd.nextLong(-spread, spread + 1)

                    val holder =
                        async(Dispatchers.Default) {
                            uow.write("Leak.hold") {
                                held.complete(Unit)
                                while (waiterStart.get() == 0L) Thread.onSpinWait()
                                val target = waiterStart.get() + shortDeadlineMs * 1_000_000L + offsetNanos
                                while (System.nanoTime() < target) Thread.onSpinWait()
                                Outcome.Ok("held")
                            }
                        }
                    held.await()
                    val waiters =
                        List(waiterCount) { w ->
                            async(Dispatchers.Default) {
                                if (w == 0) waiterStart.set(System.nanoTime())
                                uow.write("Leak.wait$w") { Outcome.Ok("waited") }
                            }
                        }

                    assertEquals(Outcome.Ok("held"), holder.await(), "holder commits (iteration $i)")
                    for ((w, waiter) in waiters.withIndex()) {
                        when (val r = waiter.await()) {
                            is Outcome.Ok -> {
                                okWaiters++
                                if (w == 0) controlNanos += stepNanos
                            }
                            is Outcome.Err -> {
                                if (w == 0) controlNanos -= stepNanos
                                assertEquals(ErrorCode.UNAVAILABLE, r.error.code, "a shed waiter is UNAVAILABLE (iteration $i)")
                                shedWaiters++
                            }
                        }
                    }

                    // The writer must be free now, whichever way the waiters ended. A leaked lock makes this probe wait
                    // out its (short) deadline and come back UNAVAILABLE.
                    dm.units.deadline = 300.milliseconds
                    val fresh = uow.write("Leak.fresh") { Outcome.Ok("free") }
                    val freshOk =
                        assertIs<Outcome.Ok<String>>(fresh, "writer leaked: a fresh write unit could not get the writer (iteration $i)")
                    assertEquals("free", freshOk.value)
                }
            } finally {
                stop.set(true)
            }
            noise.forEach { it.join() }
            println("WriterLockLeakTest iterations=$iterations waiterOk=$okWaiters waiterShed=$shedWaiters")
            assertEquals(iterations * waiterCount, okWaiters + shedWaiters)
        }
}
