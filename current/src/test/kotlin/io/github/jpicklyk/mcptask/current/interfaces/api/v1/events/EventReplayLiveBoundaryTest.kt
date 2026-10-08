package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * P8 (item ea2b9b63) replay/live boundary: a subscriber that resumes with a Last-Event-ID while rows are being
 * committed receives every row exactly once, ascending (plan section 3.7: SSE id is the seq, replay equals live).
 *
 * The overlap is FORCED, not left to scheduling: [OverlapStore] wraps the real SQLite event store and, on the first
 * replay read of a resume, commits new rows and delivers their live commit signal to the bus so that those rows are
 * visible to BOTH the replay read and the live delivery. Two orders are exercised, alternating over 20 iterations:
 * the signal arrives before the replay read returns (SIGNAL_BEFORE_READ) and after it (SIGNAL_AFTER_READ). A bus
 * that does not de-duplicate by seq at that boundary emits the injected rows twice.
 *
 * Oracle: exactly-once is the spec (plan 3.7 / test-plan S13): the delivered ids equal the table seqs after the
 * cursor, each once, ascending. No expected value is read from the implementation.
 */
class EventReplayLiveBoundaryTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val realStore: EventStore get() = db.repositoryProvider().eventStore()

    private enum class Order { SIGNAL_BEFORE_READ, SIGNAL_AFTER_READ }

    private class OverlapStore(
        private val delegate: EventStore,
        private val order: Order,
        private val injectCount: Int,
    ) : EventStore {
        lateinit var bus: ApiEventBus
        val injected = CopyOnWriteArrayList<Long>()
        private val reads = AtomicInteger(0)
        val signals = CopyOnWriteArrayList<kotlinx.coroutines.Job>()

        // Delivered from a separate coroutine: the commit signal must not run inside the replay read.
        private fun signal(committed: List<EventRecord>) {
            signals += kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { bus.committed(committed) }
        }

        override suspend fun append(records: List<EventRecord>): List<EventRecord> = delegate.append(records)

        override suspend fun maxSeq(): Long = delegate.maxSeq()

        override suspend fun readAfter(
            afterSeq: Long,
            rootIds: Set<UUID>?,
            limit: Int,
        ): List<EventRecord> {
            if (reads.incrementAndGet() != 1) return delegate.readAfter(afterSeq, rootIds, limit)
            val committed = delegate.append(List(injectCount) { row() })
            injected += committed.map { it.seq }
            if (order == Order.SIGNAL_BEFORE_READ) signal(committed)
            val result = delegate.readAfter(afterSeq, rootIds, limit)
            if (order == Order.SIGNAL_AFTER_READ) signal(committed)
            return result
        }

        private fun row(): EventRecord {
            val entity = UUID.randomUUID()
            return EventRecord(
                id = UUID.randomUUID(),
                occurredAt = Instant.now(),
                rootId = entity,
                entityKind = DomainEvent.KIND_ITEM,
                entityId = entity,
                type = DomainEvent.ITEM_UPDATED,
                data = """{"changedFields":["title"]}""",
            )
        }
    }

    private suspend fun appendRows(n: Int): List<Long> =
        realStore
            .append(
                List(n) {
                    val entity = UUID.randomUUID()
                    EventRecord(
                        id = UUID.randomUUID(),
                        occurredAt = Instant.now(),
                        rootId = entity,
                        entityKind = DomainEvent.KIND_ITEM,
                        entityId = entity,
                        type = DomainEvent.ITEM_CREATED,
                        data = """{"parentId":null}""",
                    )
                },
            ).map { it.seq }

    /** One resume with a forced overlap; returns delivered ids and the seqs the hook injected. */
    private suspend fun resumeWithOverlap(
        order: Order,
        cursorSeq: Long,
        before: List<Long>,
    ): Pair<List<Long>, List<Long>> {
        val store = OverlapStore(realStore, order, injectCount = 3)
        val bus = ApiEventBus(bufferSize = 10_000, source = store)
        store.bus = bus
        val id = "boundary-${UUID.randomUUID()}"
        val flow = bus.subscribe(id, emptySet(), lastEventId = cursorSeq, resumeRequested = true)
        val got = CopyOnWriteArrayList<Long>()
        val expectedCount = before.size + 3
        try {
            withTimeout(20.seconds) {
                coroutineScope {
                    val job = launch { flow.collect { got += it.id } }
                    while (got.size < expectedCount) {
                        delay(10)
                    }
                    delay(300) // grace window: a boundary duplicate would arrive here
                    job.cancel()
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            error("timeout order=$order cursor=$cursorSeq before=$before injected=${store.injected} got=$got")
        } finally {
            bus.unsubscribe(id)
        }
        store.signals.forEach { it.join() }
        return got.toList() to store.injected.toList()
    }

    @Test
    fun `a row visible to both the replay read and the live signal is delivered exactly once`(): Unit =
        runBlocking {
            var cursor = EventStore.SEQ_FLOOR
            val iterations = 20
            repeat(iterations) { i ->
                val order = if (i % 2 == 0) Order.SIGNAL_BEFORE_READ else Order.SIGNAL_AFTER_READ
                val before = appendRows(2)
                val (ids, injected) = resumeWithOverlap(order, cursor, before)

                assertEquals(3, injected.size, "iteration $i ($order): the hook injected rows")
                assertEquals(before + injected, ids, "iteration $i ($order): every row after the cursor, once, in seq order")
                assertEquals(ids.toSet().size, ids.size, "iteration $i ($order): no duplicate at the replay/live boundary")
                assertEquals(ids.sorted(), ids, "iteration $i ($order): ascending")
                assertTrue(ids.all { it > cursor }, "iteration $i ($order): nothing at or before the cursor")
                cursor = injected.last()
            }
        }
}
