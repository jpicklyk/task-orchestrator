package io.github.jpicklyk.mcptask.current.infrastructure

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyRecord
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.Fingerprint
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item d1cccd1a) for [IdempotencyPruner].
 *
 * Oracle: task-scope "Pruner": `pruneOnce()` = ONE unit running `deleteExpired(scope.now - 24h)` where a row is expired
 * when `created_at <= cutoff` (plan section 3.12, "expired iff created_at <= now - ttl"); `start()` prunes once at
 * startup and then every `interval`; `stop()` cancels and joins so nothing runs afterwards (plan l.281, A4). Fixed-time
 * tests use the unit-of-work clock, so the cutoff is exact to the millisecond.
 *
 * Vacuity: the "nothing runs after stop()" assertion is paired with a control that proves the same fixture IS pruned
 * while the pruner is running (same interval, same kind of row).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class IdempotencyPrunerTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val t0: Instant = Instant.parse("2026-03-01T10:00:00.123Z")
    private val ttl: Duration = Duration.ofHours(24)

    private fun unit(clock: Clock): UnitOfWork = sqlite.unitOfWork(clock)

    private fun fp(text: String): String = Fingerprint.of(JsonPrimitive(text))

    private suspend fun seed(
        uow: UnitOfWork,
        key: String,
        createdAt: Instant
    ) {
        uow.write("seed.$key") {
            stores.idempotencyStore().upsert(
                IdempotencyRecord("agent-1", "mcp.test", key, fp(key), """{"v":1,"ok":true,"value":1}""", createdAt)
            )
            Outcome.Ok(Unit)
        }
    }

    private suspend fun keys(uow: UnitOfWork): Set<String> {
        val all = mutableSetOf<String>()
        for (k in listOf("before", "at", "after", "old", "fresh", "late", "later")) {
            if (uow.read { stores.idempotencyStore().find("agent-1", "mcp.test", k) } != null) all += k
        }
        return all
    }

    // S13 - the cutoff boundary
    @Test
    fun `S13 pruneOnce deletes rows at or below now minus ttl, keeps newer rows, and returns the count`(): Unit =
        runBlocking {
            val uow = unit(Clock { t0 })
            val cutoff = t0.minus(ttl)
            seed(uow, "before", cutoff.minusMillis(1))
            seed(uow, "at", cutoff)
            seed(uow, "after", cutoff.plusMillis(1))

            val pruned = IdempotencyPruner(uow).pruneOnce()

            assertEquals(2, pruned, "rows at or below the cutoff are deleted")
            assertEquals(setOf("after"), keys(uow), "the row 1 ms newer than the cutoff survives")
            assertEquals(1, sqlite.idempotencyRecordCount())
        }

    @Test
    fun `S13 a second pruneOnce over the same data deletes nothing`(): Unit =
        runBlocking {
            val uow = unit(Clock { t0 })
            seed(uow, "old", t0.minus(ttl).minusSeconds(5))
            val pruner = IdempotencyPruner(uow)
            assertEquals(1, pruner.pruneOnce())
            assertEquals(0, pruner.pruneOnce())
        }

    @Test
    fun `S13 pruneOnce on an empty table returns zero`(): Unit =
        runBlocking {
            assertEquals(0, IdempotencyPruner(unit(Clock { t0 })).pruneOnce())
        }

    @Test
    fun `S13 a configured ttl moves the cutoff`(): Unit =
        runBlocking {
            val uow = unit(Clock { t0 })
            seed(uow, "old", t0.minus(Duration.ofHours(2)))
            seed(uow, "fresh", t0.minus(Duration.ofMinutes(30)))
            val pruned = IdempotencyPruner(uow, ttl = Duration.ofHours(1)).pruneOnce()
            assertEquals(1, pruned)
            assertEquals(setOf("fresh"), keys(uow))
        }

    @Test
    fun `S13 the default ttl keeps a row that is 23 hours old`(): Unit =
        runBlocking {
            val uow = unit(Clock { t0 })
            seed(uow, "fresh", t0.minus(Duration.ofHours(23)))
            assertEquals(0, IdempotencyPruner(uow).pruneOnce())
            assertEquals(setOf("fresh"), keys(uow))
        }

    // S13 - start / hourly / stop
    private suspend fun waitUntil(
        timeoutMs: Long = 15_000,
        condition: suspend () -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            delay(50)
        }
        return condition()
    }

    @Test
    fun `S13 start prunes at startup and then repeatedly on the interval, and stop ends the loop`(): Unit =
        runBlocking {
            val uow = unit(Clock { Instant.now() })
            val expiredAtStart = Instant.now().minus(ttl).minusSeconds(60)
            seed(uow, "old", expiredAtStart)

            val pruner = IdempotencyPruner(uow, interval = Duration.ofMillis(100))
            pruner.start()
            try {
                assertTrue(waitUntil { keys(uow).isEmpty() }, "the startup prune must delete the expired row")

                // a row that expires AFTER startup must be removed by a later periodic run
                seed(uow, "late", Instant.now().minus(ttl).minusSeconds(60))
                assertTrue(waitUntil { "late" !in keys(uow) }, "a periodic run must delete a row added after startup")
            } finally {
                pruner.stop()
            }

            // after stop(): the same kind of expired row is no longer pruned (control: it was pruned while running)
            seed(uow, "later", Instant.now().minus(ttl).minusSeconds(60))
            delay(1_000)
            assertEquals(setOf("later"), keys(uow), "nothing may run after stop()")
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

    // task-scope "Pruner": a startup prune failure is non-fatal (WARN) and the hourly loop keeps going
    @Test
    fun `S13 a failing prune does not throw out of start, the loop keeps retrying on the interval, and stop completes`(): Unit =
        runBlocking {
            val attempts = AtomicInteger(0)
            val pruner = IdempotencyPruner(FailingWrites(unit(Clock { Instant.now() }), attempts), interval = Duration.ofMillis(50))

            pruner.start() // must not throw although the startup prune fails
            try {
                assertTrue(waitUntil { attempts.get() >= 3 }, "the scheduler must survive failures and attempt again: ${attempts.get()}")
            } finally {
                pruner.stop()
            }

            val afterStop = attempts.get()
            delay(500)
            assertEquals(afterStop, attempts.get(), "no attempt may run after stop()")
        }
}
