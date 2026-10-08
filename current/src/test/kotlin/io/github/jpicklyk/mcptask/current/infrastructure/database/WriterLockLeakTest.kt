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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
            val shortDeadlineMs = 5L
            val iterations = 1_500
            var okWaiters = 0
            var shedWaiters = 0

            repeat(iterations) { i ->
                dm.units.deadline = shortDeadlineMs.milliseconds
                val held = CompletableDeferred<Unit>()
                val waiterStart = AtomicLong(0L)
                // Sweep the release instant from 1.5 ms before to 1.5 ms after the waiter's deadline.
                val offsetNanos = (-1_500_000L) + (i % 40) * 75_000L

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
                val waiter =
                    async(Dispatchers.Default) {
                        waiterStart.set(System.nanoTime())
                        uow.write("Leak.wait") { Outcome.Ok("waited") }
                    }

                assertEquals(Outcome.Ok("held"), holder.await(), "holder commits (iteration $i)")
                when (val r = waiter.await()) {
                    is Outcome.Ok -> okWaiters++
                    is Outcome.Err -> {
                        assertEquals(ErrorCode.UNAVAILABLE, r.error.code, "a shed waiter is UNAVAILABLE (iteration $i)")
                        shedWaiters++
                    }
                }

                // The writer must be free now, whichever way the waiter ended.
                dm.units.deadline = 1.seconds
                val t0 = System.nanoTime()
                val fresh = uow.write("Leak.fresh") { Outcome.Ok("free") }
                val freshMs = (System.nanoTime() - t0) / 1_000_000
                val freshOk =
                    assertIs<Outcome.Ok<String>>(fresh, "writer leaked: a fresh write unit could not get the writer (iteration $i)")
                assertEquals("free", freshOk.value)
                assertTrue(freshMs < 500, "a fresh write must not wait on a leaked lock; took ${freshMs}ms (iteration $i)")
            }
            println("WriterLockLeakTest iterations=$iterations waiterOk=$okWaiters waiterShed=$shedWaiters")
            assertEquals(iterations, okWaiters + shedWaiters)
        }
}
