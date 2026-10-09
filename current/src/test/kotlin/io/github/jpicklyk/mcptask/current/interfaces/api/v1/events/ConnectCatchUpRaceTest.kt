package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.test.InMemoryEventStore
import kotlinx.coroutines.flow.Flow
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
 * P8 re-review finding 1: [ApiEventBus.connect] catches the tail up before registering a new subscriber. A row
 * another process commits DURING that catch-up (after the last page read, before the tail is advanced) must still
 * reach the subscribers that were already connected, exactly once: the tail may only advance to what was actually
 * read and fanned out, never to a separately read newest seq.
 *
 * The store is a delegating fake that, once armed, commits one row in the middle of the catch-up: from inside
 * [EventStore.maxSeq] (before returning), or right after the last page of a read (a page shorter than its limit).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ConnectCatchUpRaceTest {
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

    private enum class Hook { MAX_SEQ, LAST_PAGE }

    /** Commits one row mid-catch-up, once, at the armed [hook]. */
    private inner class RacingStore(
        val inner: InMemoryEventStore,
    ) : EventStore {
        @Volatile
        var armed: Hook? = null

        @Volatile
        var raced: EventRecord? = null

        private suspend fun race() {
            armed = null
            raced = inner.append(listOf(row())).single()
        }

        override suspend fun append(records: List<EventRecord>): List<EventRecord> = inner.append(records)

        override suspend fun readAfter(
            afterSeq: Long,
            rootIds: Set<UUID>?,
            limit: Int,
        ): List<EventRecord> {
            val page = inner.readAfter(afterSeq, rootIds, limit)
            if (page.size < limit && armed == Hook.LAST_PAGE) race()
            return page
        }

        override suspend fun latestOfType(
            type: String,
            entityIds: Set<UUID>,
        ): Map<UUID, EventRecord> = inner.latestOfType(type, entityIds)

        override suspend fun maxSeq(): Long {
            if (armed == Hook.MAX_SEQ) race()
            return inner.maxSeq()
        }
    }

    private suspend fun Flow<ApiEvent>.drain(millis: Long = 1_000): List<ApiEvent> {
        val got = mutableListOf<ApiEvent>()
        withTimeoutOrNull(millis) { collect { got += it } }
        return got
    }

    private fun scenario(hook: Hook): Unit =
        runBlocking {
            val memory = InMemoryEventStore()
            val store = RacingStore(memory)
            val bus = ApiEventBus(bufferSize = 1000, connectionQueueSize = 256, source = store)
            bus.committed(store.append(listOf(row())))
            val existing = bus.connect("existing", emptySet())
            val start = memory.maxSeq()
            val before = store.append(listOf(row())) // committed by another process, not yet polled

            store.armed = hook
            val fresh = bus.connect("fresh", emptySet())
            bus.pump() // the next poll tick
            val later = store.append(listOf(row())).single()
            bus.pump()

            val expected = memory.all().filter { it.seq > start }.map { it.seq }
            val got = existing.drain().map { it.id }
            assertTrue(got.none { it <= start }, "nothing at or below the existing subscriber's start: $got")
            assertEquals(expected, got, "the existing subscriber gets every committed row exactly once (raced=${store.raced?.seq})")
            assertTrue(before.single().seq in got && later.seq in got)
            assertTrue(fresh.drain().map { it.id }.contains(later.seq), "the new connection gets rows committed after it connected")
        }

    @Test
    fun `a row committed while connect reads the newest seq still reaches existing subscribers once`(): Unit = scenario(Hook.MAX_SEQ)

    @Test
    fun `a row committed right after connect reads its last page still reaches existing subscribers once`(): Unit = scenario(Hook.LAST_PAGE)
}
