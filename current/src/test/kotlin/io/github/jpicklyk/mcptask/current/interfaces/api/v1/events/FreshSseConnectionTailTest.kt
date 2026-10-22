package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.test.InMemoryEventStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P8 review finding 2: rows another process committed while nobody was subscribed must not reach a NEW connection
 * through the tail (stale foreign rows, and a spurious `queue_overflow` when the backlog exceeds the per-connection
 * queue). `GET /api/v1/events` opens connections with [ApiEventBus.connect], which catches the tail up before
 * registering the subscriber: a fresh connection sees only rows committed after it connected; a resume replays the
 * backlog from the table, once, with no overflow sentinel.
 *
 * "Foreign" rows are appended straight to the store without the local commit signal, exactly as rows another
 * process commits appear to this bus.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FreshSseConnectionTailTest {
    private val root = UUID.randomUUID()

    private fun row(): EventRecord =
        EventRecord(
            id = UUID.randomUUID(),
            occurredAt = Instant.now(),
            rootId = root,
            entityKind = DomainEvent.KIND_ITEM,
            entityId = UUID.randomUUID(),
            type = DomainEvent.ITEM_CREATED,
            data = "{}",
        )

    private suspend fun InMemoryEventStore.appendRows(count: Int): List<EventRecord> = append(List(count) { row() })

    /** Drains what the connection emits within [millis]. */
    private suspend fun kotlinx.coroutines.flow.Flow<ApiEvent>.drain(millis: Long = 1_500): List<ApiEvent> {
        val got = mutableListOf<ApiEvent>()
        withTimeoutOrNull(millis) { collect { got += it } }
        return got
    }

    @Test
    fun `a fresh connection receives only rows committed after it connected and no queue_overflow`(): Unit =
        runBlocking {
            val store = InMemoryEventStore()
            val bus = ApiEventBus(bufferSize = 1000, connectionQueueSize = 256, source = store)
            // A local commit while nobody listens: the tail is known.
            bus.committed(store.appendRows(1))
            // Another process commits more rows than one connection's queue holds.
            store.appendRows(300)

            val flow = bus.connect("fresh", emptySet())
            bus.pump() // a poll tick after the connection opened
            val fresh = store.appendRows(1).single()
            bus.pump()

            val got = flow.drain()
            assertTrue(got.none { it.event == ApiEventType.SYNC_LOST }, "no sync.lost on a fresh connection: ${got.take(3)}")
            assertEquals(listOf(fresh.seq), got.map { it.id }, "only the row committed after connecting may arrive")
        }

    @Test
    fun `a resume replays a foreign backlog larger than the queue once with no queue_overflow`(): Unit =
        runBlocking {
            val store = InMemoryEventStore()
            val bus = ApiEventBus(bufferSize = 1000, connectionQueueSize = 256, source = store)
            val cursor = store.appendRows(1).single()
            bus.committed(listOf(cursor))
            val backlog = store.appendRows(300)

            val flow = bus.connect("resumed", emptySet(), lastEventId = cursor.seq)
            val got = flow.drain()

            assertTrue(got.none { it.event == ApiEventType.SYNC_LOST }, "the backlog is inside the replay window: no sync.lost")
            assertEquals(backlog.map { it.seq }, got.map { it.id }, "each backlog row is replayed exactly once, in order")
        }

    @Test
    fun `a fresh connection beside a live subscriber leaves that subscriber's catch-up intact`(): Unit =
        runBlocking {
            val store = InMemoryEventStore()
            val bus = ApiEventBus(bufferSize = 1000, connectionQueueSize = 256, source = store)
            bus.committed(store.appendRows(1))
            val existing = bus.connect("existing", emptySet())
            val foreign = store.appendRows(3) // committed by another process, not yet polled

            val fresh = bus.connect("fresh", emptySet())
            val later = store.appendRows(1).single()
            bus.pump()

            assertEquals((foreign + later).map { it.seq }, existing.drain().map { it.id }, "the existing subscriber gets every row")
            assertEquals(listOf(later.seq), fresh.drain().map { it.id }, "the new connection gets only the later row")
        }
}
