package io.github.jpicklyk.mcptask.current.infrastructure.telemetry

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.port.CallLogStore
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Batched, off-path writer of `call_log` rows (plan section 3.8).
 *
 * [submit] is the [CallLogSink] a transport calls once per finished call: it offers the record to a bounded queue
 * and returns. It never blocks, suspends or throws; when the queue is full the record is dropped and [dropped] is
 * incremented. The loop reports pending drops in one WARN notice per [flushInterval] carrying the number dropped
 * since the previous notice (also while the loop is parked in a slow store), and [stop] emits a final notice if any
 * are pending.
 *
 * A background loop writes whenever [batchSize] rows are queued or [flushInterval] has elapsed with rows waiting,
 * one write unit (`CallLog.append`) per batch of at most [batchSize]. A failed batch is WARN-logged, counted in
 * [failed] and dropped (the unit runner already retries BUSY); the loop carries on. [stop] drains what is queued,
 * bounded by [drainTimeout].
 *
 * The writer runs on its own scope outside any call, so its own unit never records itself nor inflates a call
 * retry count.
 */
class CallLogWriter(
    private val unitOfWork: UnitOfWork,
    private val store: CallLogStore,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL,
    private val drainTimeout: Duration = DEFAULT_DRAIN_TIMEOUT
) : CallLogSink {
    private val logger = LoggerFactory.getLogger(CallLogWriter::class.java)
    private val queue = Channel<CallLogRecord>(capacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val writtenCount = AtomicLong()
    private val droppedCount = AtomicLong()
    private val failedCount = AtomicLong()
    private val duplicateCount = AtomicLong()
    private val noticedDropped = AtomicLong()
    private val clock = TimeSource.Monotonic
    private val startMark = clock.markNow()

    @Volatile
    private var lastDropNoticeAt: Duration = -flushInterval - 1.seconds

    @Volatile
    private var job: Job? = null

    @Volatile
    private var ticker: Job? = null

    /** Rows inserted. */
    val written: Long get() = writtenCount.get()

    /** Rows dropped because the queue was full (or after stop). */
    val dropped: Long get() = droppedCount.get()

    /** Rows lost in batches that failed. */
    val failed: Long get() = failedCount.get()

    /** Rows ignored because their req_id already existed. */
    val duplicates: Long get() = duplicateCount.get()

    override fun submit(record: CallLogRecord) {
        try {
            if (queue.trySend(record).isSuccess) return
            // The loop (or stop) reports the drop in its next notice; submit itself never logs.
            droppedCount.incrementAndGet()
        } catch (e: Exception) {
            // Cancellation is never swallowed; any other fault here must not reach the call.
            e.rethrowIfCancellation()
            droppedCount.incrementAndGet()
        }
    }

    /** Starts the background flush loop. */
    fun start() {
        check(job == null) { "CallLogWriter is already started" }
        job = scope.launch { runLoop() }
        ticker =
            scope.launch {
                while (true) {
                    delay(flushInterval)
                    noticeDrops(force = false)
                }
            }
    }

    /**
     * Stops accepting rows, writes what is queued (waiting at most [drainTimeout]) and ends the loop. On timeout the
     * loop is cancelled and joined, so no batch write is in flight when this returns; whatever could not be written
     * in time is counted in [dropped].
     */
    suspend fun stop() {
        val running = job
        ticker?.cancel()
        queue.close()
        if (running != null) {
            val finished = withTimeoutOrNull(drainTimeout) { running.join() } != null
            if (!finished) {
                running.cancel()
                running.join()
                var left = 0L
                while (queue.tryReceive().isSuccess) left++
                if (left > 0) {
                    droppedCount.addAndGet(left)
                    logger.warn("Call log writer stopped with {} unwritten row(s)", left)
                }
            }
        } else {
            withTimeoutOrNull(drainTimeout) { flushNow() }
        }
        noticeDrops(force = true)
        scope.coroutineContext[Job]?.cancel()
        job = null
    }

    /** Writes every queued row now, in batches of at most [batchSize], and returns how many rows were handed to the store. */
    suspend fun flushNow(): Int {
        var total = 0
        while (true) {
            val batch = takeBatch()
            if (batch.isEmpty()) return total
            writeBatch(batch)
            total += batch.size
        }
    }

    private suspend fun runLoop() {
        try {
            while (true) {
                val first = queue.receiveCatching().getOrNull() ?: return
                val batch = ArrayList<CallLogRecord>(batchSize)
                batch.add(first)
                try {
                    // Gather until the batch is full or flushInterval has elapsed since the first row.
                    val mark = clock.markNow()
                    while (batch.size < batchSize) {
                        val remaining = flushInterval - mark.elapsedNow()
                        if (remaining <= Duration.ZERO) break
                        val next = withTimeoutOrNull(remaining) { queue.receiveCatching() } ?: break
                        batch.add(next.getOrNull() ?: break)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Rows gathered but not yet handed to the store are abandoned by the drain timeout.
                    droppedCount.addAndGet(batch.size.toLong())
                    throw e
                }
                writeBatch(batch)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Call log writer loop ended unexpectedly: {}", e.message)
        }
    }

    private fun takeBatch(): List<CallLogRecord> {
        val batch = ArrayList<CallLogRecord>(batchSize)
        while (batch.size < batchSize) {
            val next = queue.tryReceive().getOrNull() ?: break
            batch.add(next)
        }
        return batch
    }

    private suspend fun writeBatch(batch: List<CallLogRecord>) {
        writeLock.withLock {
            try {
                val outcome = unitOfWork.write("CallLog.append") { Outcome.Ok(store.appendCounted(batch)) }
                when (outcome) {
                    is Outcome.Ok -> {
                        val result = outcome.value
                        writtenCount.addAndGet(result.inserted.toLong())
                        val duplicates = batch.size - result.inserted - result.rejected
                        if (duplicates > 0) {
                            duplicateCount.addAndGet(duplicates.toLong())
                            logger.debug("Call log ignored {} duplicate req_id row(s)", duplicates)
                        }
                        if (result.rejected > 0) {
                            failedCount.addAndGet(result.rejected.toLong())
                            logger.warn("Call log refused {} row(s) for a reason other than a duplicate req_id", result.rejected)
                        }
                    }
                    is Outcome.Err -> {
                        failedCount.addAndGet(batch.size.toLong())
                        logger.warn("Call log batch of {} row(s) failed and was dropped: {}", batch.size, outcome.error.message)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by the drain timeout: the batch is abandoned, and stop() joins before returning.
                droppedCount.addAndGet(batch.size.toLong())
                throw e
            } catch (e: Exception) {
                failedCount.addAndGet(batch.size.toLong())
                logger.warn("Call log batch of {} row(s) failed and was dropped: {}", batch.size, e.message)
            }
        }
    }

    /** Logs the number of rows dropped since the previous notice; at most once per interval unless [force]. */
    @Synchronized
    private fun noticeDrops(force: Boolean) {
        val now = startMark.elapsedNow()
        if (!force && now - lastDropNoticeAt < flushInterval) return
        val total = droppedCount.get()
        val since = total - noticedDropped.get()
        if (since <= 0) return
        noticedDropped.set(total)
        lastDropNoticeAt = now
        logger.warn("Call log dropped {} row(s) since the last notice (queue capacity {}), {} in total", since, capacity, total)
    }

    companion object {
        const val DEFAULT_CAPACITY = 10_000
        const val DEFAULT_BATCH_SIZE = 100
        val DEFAULT_FLUSH_INTERVAL: Duration = 250.milliseconds
        val DEFAULT_DRAIN_TIMEOUT: Duration = 5.seconds
    }
}
