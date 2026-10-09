package io.github.jpicklyk.mcptask.current.infrastructure.telemetry

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.LogCapture
import io.github.jpicklyk.mcptask.current.test.RecordingCallLogStore
import io.github.jpicklyk.mcptask.current.test.sampleCallLogRecord
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Independent tests for item 8abb69e2 (P10): [CallLogWriter]. Scenario ids follow the frozen test-plan.
 *
 * Oracles: task-scope What-to-build item 6 and acceptance A4 (frozen): `submit` is non-suspending and never blocks or
 * throws; a bounded queue (overflow increments `dropped` and WARN-logs once per flush interval carrying the count); a
 * loop flushes whenever `batchSize` rows are queued or `flushInterval` elapses, one unit of at most `batchSize` rows per
 * batch; a failed batch is WARN-logged and dropped and the loop continues; `stop()` drains the queue bounded by
 * `drainTimeout`; counters `written`, `dropped`, `failed`, `duplicates`. Performance-baseline section 4 gives the
 * throughput and tolerance numbers.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CallLogWriterTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private suspend fun waitUntil(
        timeoutMs: Long = 10_000,
        condition: () -> Boolean
    ) {
        withTimeout(timeoutMs) { while (!condition()) delay(5) }
    }

    @Test
    fun `counters start at zero`() {
        val writer = CallLogWriter(sqlite.unitOfWork(), RecordingCallLogStore())
        assertEquals(0L, writer.written)
        assertEquals(0L, writer.dropped)
        assertEquals(0L, writer.failed)
        assertEquals(0L, writer.duplicates)
    }

    @Test
    fun `S8 without a flush loop the queue holds exactly capacity records and the rest are dropped`() =
        runBlocking {
            val writer =
                CallLogWriter(sqlite.unitOfWork(), RecordingCallLogStore(), capacity = 10, batchSize = 1000, flushInterval = 1.hours)
            val elapsedMs = measureTimeMillis { repeat(15) { writer.submit(sampleCallLogRecord(testReqId(it))) } }
            assertTrue(elapsedMs < 1000, "submit never blocks, took $elapsedMs ms")
            assertEquals(5L, writer.dropped, "15 submits into a queue of 10 with nothing draining it drop 5")
            assertEquals(0L, writer.written)
        }

    @Test
    fun `S8 with the flush loop parked in a suspended store the overflow is dropped and warned per interval`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val store = RecordingCallLogStore(gate)
            val interval = 1.seconds
            val writer =
                CallLogWriter(
                    sqlite.unitOfWork(),
                    store,
                    capacity = 10,
                    batchSize = 5,
                    flushInterval = interval,
                    drainTimeout = 500.milliseconds
                )
            val capture = LogCapture().attach()
            try {
                writer.start()
                repeat(5) { writer.submit(sampleCallLogRecord(testReqId(it))) } // exactly batchSize: the size trigger fires
                waitUntil(5_000) { store.entered.get() >= 1 }
                val elapsedMs = measureTimeMillis { repeat(30) { writer.submit(sampleCallLogRecord(testReqId(100 + it))) } }
                assertTrue(elapsedMs < 1000, "30 submits against a stuck store must return at once, took $elapsedMs ms")
                assertEquals(20L, writer.dropped, "the loop is parked in the store: 10 are queued and 30 - 10 are dropped")

                // The first overflow is logged at once; the further drops of the burst fall inside the same flush interval.
                waitUntil(interval.inWholeMilliseconds * 4) { capture.events.any { it.level == Level.WARN } }
                val firstLines = capture.events.filter { it.level == Level.WARN }
                assertEquals(1, firstLines.size, "one WARN line for a burst inside one flush interval: ${firstLines.map { it.message }}")
                assertTrue(
                    Regex("\\d+").containsMatchIn(firstLines.single().message),
                    "the line carries a count: ${firstLines.single().message}"
                )
                delay(interval.inWholeMilliseconds * 2 + 200) // two more intervals
                val allLines = capture.events.filter { it.level == Level.WARN }
                assertTrue(allLines.size <= 3, "at most one WARN per flush interval (1 + 2 intervals): ${allLines.map { it.message }}")
            } finally {
                capture.detach()
                gate.complete(Unit)
                withTimeout(10_000) { writer.stop() }
            }
        }

    @Test
    fun `S8 a suspended store never delays callers even with a large queue`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val writer =
                CallLogWriter(
                    sqlite.unitOfWork(),
                    RecordingCallLogStore(gate),
                    capacity = 10_000,
                    batchSize = 100,
                    flushInterval = 20.milliseconds,
                    drainTimeout = 300.milliseconds
                )
            try {
                writer.start()
                val elapsedMs = measureTimeMillis { repeat(5_000) { writer.submit(sampleCallLogRecord(testReqId(it))) } }
                assertTrue(elapsedMs < 2_000, "5000 submits while the store is stuck must not wait on it, took $elapsedMs ms")
            } finally {
                gate.complete(Unit)
                withTimeout(10_000) { writer.stop() }
            }
        }

    @Test
    fun `S9 a failing batch is warned and dropped, the counter moves, and the next batch is written`() =
        runBlocking {
            val store = RecordingCallLogStore(failFirstCalls = 1)
            val writer = CallLogWriter(sqlite.unitOfWork(), store, capacity = 100, batchSize = 100, flushInterval = 30.milliseconds)
            val capture = LogCapture().attach()
            try {
                writer.start()
                val lost = sampleCallLogRecord(testReqId(1))
                writer.submit(lost)
                waitUntil { writer.failed >= 1L }
                assertEquals(0L, writer.written, "nothing was written by the failed batch")
                assertTrue(capture.events.any { it.level == Level.WARN }, "a failed batch is WARN-logged")

                val kept = sampleCallLogRecord(testReqId(2))
                writer.submit(kept)
                waitUntil { writer.written >= 1L }
                assertEquals(listOf(kept.reqId), store.records.map { it.reqId }, "the lost batch is dropped, never retried or re-queued")
                assertEquals(1L, writer.failed)
                assertEquals(1L, writer.written)
            } finally {
                capture.detach()
                withTimeout(10_000) { writer.stop() }
            }
        }

    @Test
    fun `S11 250 submits are appended in batches of at most 100 and all 250 are written`() =
        runBlocking {
            val store = RecordingCallLogStore()
            val writer = CallLogWriter(sqlite.unitOfWork(), store, capacity = 10_000, batchSize = 100, flushInterval = 50.milliseconds)
            try {
                writer.start()
                val submitted = (1..250).map { sampleCallLogRecord(testReqId(it)) }
                submitted.forEach { writer.submit(it) }
                waitUntil { store.records.size >= 250 }
                assertEquals(250, store.records.size)
                assertTrue(store.batches.all { it.size in 1..100 }, "every append is 1..100 rows: ${store.batches.map { it.size }}")
                assertEquals(submitted.map { it.reqId }.toSet(), store.records.map { it.reqId }.toSet())
                waitUntil { writer.written == 250L }
                assertEquals(0L, writer.dropped)
                assertEquals(0L, writer.failed)
            } finally {
                withTimeout(10_000) { writer.stop() }
            }
        }

    @Test
    fun `S11 30 submits with nothing after them are flushed by the timer within three intervals`() =
        runBlocking {
            val interval = 200.milliseconds
            val store = RecordingCallLogStore()
            val writer = CallLogWriter(sqlite.unitOfWork(), store, capacity = 10_000, batchSize = 100, flushInterval = interval)
            try {
                writer.start()
                repeat(30) { writer.submit(sampleCallLogRecord(testReqId(it))) }
                val started = System.nanoTime()
                // Oracle: written within 3 x flushInterval; one second of slack covers scheduler jitter on a loaded CI host.
                waitUntil(timeoutMs = 3 * interval.inWholeMilliseconds + 1_000) { writer.written == 30L }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertEquals(30, store.records.size)
                assertTrue(store.batches.all { it.size <= 100 })
                assertTrue(elapsedMs < 3 * interval.inWholeMilliseconds + 1_000, "flushed after $elapsedMs ms")
            } finally {
                withTimeout(10_000) { writer.stop() }
            }
        }

    @Test
    fun `S15 stop drains rows that no size or timer trigger flushed`() =
        runBlocking {
            val writer =
                CallLogWriter(
                    sqlite.unitOfWork(),
                    SqliteCallLogStore(sqlite.databaseManager),
                    capacity = 10_000,
                    batchSize = 100,
                    flushInterval = 1.hours,
                    drainTimeout = 5.seconds
                )
            writer.start()
            repeat(120) { writer.submit(sampleCallLogRecord(testReqId(it))) }
            withTimeout(30_000) { writer.stop() }
            assertEquals(120, CallLogRows.count(sqlite.jdbcUrl), "all 120 rows reach the table once stop() returns")
            assertEquals(120L, writer.written)
            assertEquals(0L, writer.dropped)
            assertEquals(0L, writer.failed)
        }

    @Test
    fun `S15 stop is bounded by the drain timeout when the store never answers`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val writer =
                CallLogWriter(
                    sqlite.unitOfWork(),
                    RecordingCallLogStore(gate),
                    capacity = 100,
                    batchSize = 100,
                    flushInterval = 1.hours,
                    drainTimeout = 300.milliseconds
                )
            try {
                writer.start()
                repeat(10) { writer.submit(sampleCallLogRecord(testReqId(it))) }
                val elapsedMs = measureTimeMillis { withTimeout(10_000) { writer.stop() } }
                assertTrue(elapsedMs < 8_000, "stop() must give up after the drain timeout, took $elapsedMs ms")
                assertEquals(0L, writer.written)
            } finally {
                gate.complete(Unit)
            }
        }

    @Test
    fun `eight concurrent producers lose nothing under a roomy queue`() =
        runBlocking {
            val writer =
                CallLogWriter(
                    sqlite.unitOfWork(),
                    SqliteCallLogStore(sqlite.databaseManager),
                    capacity = 10_000,
                    batchSize = 100,
                    flushInterval = 25.milliseconds
                )
            writer.start()
            (0 until 8)
                .map { agent ->
                    async(Dispatchers.Default) {
                        repeat(500) { n -> writer.submit(sampleCallLogRecord("p" + agent + n.toString().padStart(5, '0') + "a")) }
                    }
                }.awaitAll()
            withTimeout(60_000) { writer.stop() }
            assertEquals(4000, CallLogRows.count(sqlite.jdbcUrl))
            assertEquals(4000L, writer.written)
            assertEquals(0L, writer.dropped)
            assertEquals(0L, writer.failed)
        }

    @Test
    fun `S12 duplicate req_ids are counted as duplicates and never fail the batch`() =
        runBlocking {
            val writer =
                CallLogWriter(
                    sqlite.unitOfWork(),
                    SqliteCallLogStore(sqlite.databaseManager),
                    capacity = 100,
                    batchSize = 100,
                    flushInterval = 1.hours
                )
            writer.submit(sampleCallLogRecord("dup00001", tool = "first"))
            writer.submit(sampleCallLogRecord("dup00001", tool = "second"))
            writer.submit(sampleCallLogRecord("dup00002", tool = "other"))
            writer.flushNow()
            assertEquals(2, CallLogRows.count(sqlite.jdbcUrl))
            assertEquals(2L, writer.written, "written counts rows inserted")
            assertEquals(1L, writer.duplicates, "the ignored duplicate is counted")
            assertEquals(0L, writer.failed, "a duplicate is not a failure")
        }

    @Test
    fun `F7 a CHECK-violating row counts as failed and warns, a duplicate counts as a duplicate, the rest is written`() =
        runBlocking {
            val writer =
                CallLogWriter(
                    sqlite.unitOfWork(),
                    SqliteCallLogStore(sqlite.databaseManager),
                    capacity = 100,
                    batchSize = 100,
                    flushInterval = 1.hours
                )
            val capture = LogCapture().attach()
            try {
                writer.submit(sampleCallLogRecord("f7w00001"))
                writer.submit(sampleCallLogRecord("f7w00001", tool = "dup"))
                writer.submit(sampleCallLogRecord("f7w00002").copy(latencyMs = -1))
                writer.submit(sampleCallLogRecord("f7w00003"))
                writer.flushNow()
                assertEquals(2, CallLogRows.count(sqlite.jdbcUrl))
                assertEquals(2L, writer.written)
                assertEquals(1L, writer.duplicates, "only the primary-key conflict is a duplicate")
                assertEquals(1L, writer.failed, "the CHECK-violating row is failed")
                assertTrue(capture.events.any { it.level == Level.WARN }, "a rejected row is logged at WARN")
            } finally {
                capture.detach()
            }
        }

    @Test
    fun `flushNow on an empty queue writes nothing and does not throw`() =
        runBlocking {
            val store = RecordingCallLogStore()
            val writer = CallLogWriter(sqlite.unitOfWork(), store)
            writer.flushNow()
            assertEquals(0, store.batches.size, "no append for an empty queue")
            assertEquals(0L, writer.written)
        }

    // ------------------------------------------------------------------ F5

    private fun warnMessages(capture: LogCapture): List<String> = capture.events.filter { it.level == Level.WARN }.map { it.message }

    /** The drop count a notice carries: the first integer in the message. */
    private fun noticedCount(message: String): Long = Regex("\\d+").find(message)?.value?.toLong() ?: 0L

    @Test
    fun `F5 the notices after a burst together report every dropped row and stop emits the pending remainder`() =
        runBlocking {
            val store = RecordingCallLogStore()
            // Not started: 25 submits into a queue of 10 drop exactly 15 with nothing draining.
            val writer = CallLogWriter(sqlite.unitOfWork(), store, capacity = 10, batchSize = 1000, flushInterval = 1.hours)
            val capture = LogCapture().attach()
            try {
                repeat(25) { writer.submit(sampleCallLogRecord(testReqId(it))) }
                assertEquals(15L, writer.dropped)
                writer.start()
                withTimeout(30_000) { writer.stop() }
                val notices = warnMessages(capture)
                assertTrue(notices.isNotEmpty(), "a final notice is emitted when drops are pending at stop()")
                assertEquals(
                    15L,
                    notices.sumOf { noticedCount(it) },
                    "the notices together report all 15 dropped rows, not just the first: $notices"
                )
                assertEquals(15L, writer.dropped, "cumulative dropped is unchanged by reporting")
            } finally {
                capture.detach()
            }
        }

    @Test
    fun `F5 a burst of 20 drops while the store is stuck is reported in full once the loop runs again`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val store = RecordingCallLogStore(gate)
            val interval = 500.milliseconds
            val writer =
                CallLogWriter(
                    sqlite.unitOfWork(),
                    store,
                    capacity = 10,
                    batchSize = 5,
                    flushInterval = interval,
                    drainTimeout = 5.seconds
                )
            val capture = LogCapture().attach()
            try {
                writer.start()
                repeat(5) { writer.submit(sampleCallLogRecord(testReqId(it))) }
                waitUntil(5_000) { store.entered.get() >= 1 }
                repeat(30) { writer.submit(sampleCallLogRecord(testReqId(100 + it))) }
                assertEquals(20L, writer.dropped)
                gate.complete(Unit)
                // Several intervals for the loop to publish the pending count; at most one notice per interval.
                delay(interval * 6)
                withTimeout(30_000) { writer.stop() }
                val notices = warnMessages(capture)
                assertEquals(20L, notices.sumOf { noticedCount(it) }, "notices together report N=20: $notices")
                assertEquals(20L, writer.dropped)
            } finally {
                capture.detach()
                gate.complete(Unit)
            }
        }

    // ------------------------------------------------------------------ F6

    @Test
    fun `F6 stop with a store that never returns comes back within the drain timeout and counts the abandoned rows as dropped`() =
        runBlocking {
            val inFlight =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val entered =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val hangingStore =
                object : io.github.jpicklyk.mcptask.current.application.port.CallLogStore {
                    override suspend fun append(records: List<io.github.jpicklyk.mcptask.current.application.port.CallLogRecord>): Int {
                        entered.incrementAndGet()
                        inFlight.set(true)
                        try {
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            inFlight.set(false)
                        }
                    }
                }
            val drainTimeout = 500.milliseconds
            val writer =
                CallLogWriter(
                    sqlite.unitOfWork(),
                    hangingStore,
                    capacity = 100,
                    batchSize = 100,
                    flushInterval = 1.hours,
                    drainTimeout = drainTimeout
                )
            writer.start()
            repeat(10) { writer.submit(sampleCallLogRecord(testReqId(it))) }
            val mark =
                kotlin.time.TimeSource.Monotonic
                    .markNow()
            withTimeout(30_000) { writer.stop() }
            val elapsed = mark.elapsedNow()
            assertTrue(elapsed < drainTimeout + 3.seconds, "stop() gives up after the drain timeout (plus slack), took $elapsed")
            assertTrue(!inFlight.get(), "stop() does not return while a batch write is still in flight")
            assertEquals(0L, writer.written)
            assertEquals(10L, writer.dropped, "the 10 rows abandoned by the timeout count as dropped")
        }
}
