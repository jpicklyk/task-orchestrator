package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Cancellation sibling of [WriterLockLeakTest] (item 9343ad8d). Oracle: the task spec for cancellation ("if the caller's
 * coroutine is cancelled at any moment while a write unit is waiting for the writer, or has just been granted it, then
 * once the caller has finished cancelling, the writer must be free") and the kotlinx rule that a cancelled lock waiter
 * may still have been granted the lock.
 *
 * A holder unit occupies the writer. Many waiter units queue behind it in a separate Job. A platform thread cancels that
 * Job at a swept offset from the instant the holder's body ends (i.e. right around the moment the first waiter is granted
 * the writer, and across the chain of hand-offs that follow). After every iteration the cancelled Job is joined and a
 * fresh write unit must return Ok promptly; a leaked writer makes it come back UNAVAILABLE after its deadline.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@Tag("serial")
class WriterLockCancellationLeakTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val dm get() = db.databaseManager

    private class Cancel(
        val job: Job,
        val holderEnd: AtomicLong,
        val offsetNanos: Long,
        val done: CompletableDeferred<Unit>
    )

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `writer is free after a waiting write unit is cancelled around the instant it is granted`(): Unit =
        runBlocking {
            val uow = db.uow()
            val stop = AtomicBoolean(false)
            val request = AtomicReference<Cancel?>(null)
            val canceller =
                Thread {
                    while (!stop.get()) {
                        val c = request.get()
                        if (c == null) {
                            Thread.onSpinWait()
                            continue
                        }
                        request.set(null)
                        while (c.holderEnd.get() == 0L) Thread.onSpinWait()
                        val target = c.holderEnd.get() + c.offsetNanos
                        while (System.nanoTime() < target) Thread.onSpinWait()
                        c.job.cancel()
                        c.done.complete(Unit)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

            val iterations = ITERATIONS
            val waiterCount = 24
            val rnd =
                java.util.concurrent.ThreadLocalRandom
                    .current()
            try {
                repeat(iterations) { i ->
                    dm.units.deadline = 5_000.milliseconds
                    val held = CompletableDeferred<Unit>()
                    val holderEnd = AtomicLong(0L)
                    val holder =
                        async(Dispatchers.Default) {
                            uow.write("CancelLeak.hold") {
                                held.complete(Unit)
                                val target = System.nanoTime() + 300_000L
                                while (System.nanoTime() < target) Thread.onSpinWait()
                                holderEnd.set(System.nanoTime())
                                Outcome.Ok("held")
                            }
                        }
                    held.await()
                    val scope = CoroutineScope(Job() + Dispatchers.Default)
                    repeat(waiterCount) { w ->
                        scope.async { uow.write("CancelLeak.wait$w") { Outcome.Ok("waited") } }
                    }
                    // Give the waiters time to queue on the writer.
                    val queued = System.nanoTime() + 150_000L
                    while (System.nanoTime() < queued) Thread.onSpinWait()

                    val done = CompletableDeferred<Unit>()
                    // Sweep the cancel instant across 0..90 us after the holder's body ends, covering the grant and the
                    // first hand-offs of the waiter chain.
                    request.set(Cancel(scope.coroutineContext[Job]!!, holderEnd, rnd.nextLong(0L, 90_000L), done))
                    holder.await()
                    done.await()
                    scope.coroutineContext[Job]!!.join()

                    dm.units.deadline = 300.milliseconds
                    val fresh = uow.write("CancelLeak.fresh") { Outcome.Ok("free") }
                    val ok =
                        assertIs<Outcome.Ok<String>>(
                            fresh,
                            "writer leaked: a fresh write unit could not get the writer after a cancel (iteration $i): $fresh"
                        )
                    assertEquals("free", ok.value)
                }
            } finally {
                stop.set(true)
                canceller.join()
            }
        }

    private companion object {
        const val ITERATIONS = 6_000
    }
}
